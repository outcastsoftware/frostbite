#include <jni.h>
#include <android/log.h>i 
#include <dobby.h>
#include <BNM/Loading.hpp>
#include <BNM/Image.hpp>
#include <BNM/Class.hpp>
#include <BNM/Field.hpp>
#include <BNM/MethodBase.hpp>
#include <array>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <utility>

#if defined(__aarch64__) || defined(__arm__)
// Universal trampoline (see trampoline_arm64.S / trampoline_arm32.S). The context layout
// must match the assembly for the target architecture.
extern "C" void *frostbite_origin_table[];
extern "C" void (*frostbite_slot_entries[])();
extern "C" uint32_t frostbite_slot_stackbytes[];
#endif
#if defined(__aarch64__)
struct alignas(16) TrampolineCtx {
    uint64_t gp[9];       // 0x00  x0..x8
    uint64_t pad0;        // 0x48
    __int128_t fp[8];     // 0x50  q0..q7
    uint64_t slot;        // 0xD0
    uint64_t lr;          // 0xD8
    uint64_t ret_gp[2];   // 0xE0  return x0, x1  (<=16B struct returns are contiguous here)
    __int128_t ret_fp[4]; // 0xF0  return q0..q3  (HFA struct returns)
    uint64_t stackCopy;   // 0x130 pointer to the copied stack arguments
    uint64_t callerX19;   // 0x138 (asm only) caller's x19
    uint64_t callerX20;   // 0x140 (asm only) caller's x20
    uint64_t pad1;        // 0x148
};
static_assert(sizeof(TrampolineCtx) == 0x150, "TrampolineCtx must match trampoline_arm64.S");
#elif defined(__arm__)
struct alignas(8) TrampolineCtx {
    uint32_t gp[4];       // 0x00  r0..r3
    uint32_t slot;        // 0x10
    uint32_t lr;          // 0x14
    uint32_t ret[2];      // 0x18  return r0, r1
    uint32_t stackCopy;   // 0x20  pointer to the copied stack arguments
    uint32_t callerR4;    // 0x24  (asm only)
    uint32_t callerR5;    // 0x28  (asm only)
    uint32_t origin;      // 0x2C  (asm only) original function pointer
    uint32_t pad[4];      // 0x30
};
static_assert(sizeof(TrampolineCtx) == 0x40, "TrampolineCtx must match trampoline_arm32.S");
#endif

namespace {
constexpr int kSlots = 32;
constexpr size_t kObjectHeader = sizeof(BNM::IL2CPP::Il2CppObject);
JavaVM *vm = nullptr;
jclass bridgeClass = nullptr;
jclass callbackClass = nullptr;
jmethodID onReadyMethod = nullptr;
jmethodID headMethod = nullptr;
jmethodID tailMethod = nullptr;
jmethodID headStaticMethod = nullptr;
jmethodID tailStaticMethod = nullptr;
jmethodID cancelledMethod = nullptr;
jmethodID valueMethod = nullptr;
jmethodID objectAddressMethod = nullptr;
jclass objectClass = nullptr;
std::mutex installMutex;

// Universal trampoline dispatch: Java entry points and boxing helpers.
jmethodID headUniversalMethod = nullptr;
jmethodID tailUniversalMethod = nullptr;
jclass objectBaseClass = nullptr;      // java/lang/Object (array element type)
jclass il2cppObjectCtorClass = nullptr;
jmethodID il2cppObjectCtor = nullptr;  // Il2CppObject(long)
jclass boxIntClass = nullptr, boxLongClass = nullptr, boxFloatClass = nullptr,
       boxDoubleClass = nullptr, boxBoolClass = nullptr;
jmethodID boxIntValueOf = nullptr, boxLongValueOf = nullptr, boxFloatValueOf = nullptr,
          boxDoubleValueOf = nullptr, boxBoolValueOf = nullptr;
jmethodID unboxIntValue = nullptr, unboxLongValue = nullptr, unboxFloatValue = nullptr,
          unboxDoubleValue = nullptr, unboxBoolValue = nullptr;

struct Slot {
    void *address = nullptr;
    void *original = nullptr;
    int kind = -1;
    int argumentCount = -1;
};
std::array<Slot, kSlots> slots;

void logError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, "Frostbite", "%s", message);
}

class AttachedEnv {
public:
    AttachedEnv() {
        if (!vm) return;
        if (vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) attached_ = true;
            else env_ = nullptr;
        }
    }
    ~AttachedEnv() { if (attached_) vm->DetachCurrentThread(); }
    JNIEnv *get() const { return env_; }
private:
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

bool clearJavaException(JNIEnv *env) {
    if (!env || !env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    logError("Java hook callback threw; calling original IL2CPP method");
    return true;
}

jobject dispatchHead(JNIEnv *env, int slot, void *self, uintptr_t a0, uintptr_t a1) {
    if (!env || !headMethod) return nullptr;
    jobject result = env->CallStaticObjectMethod(bridgeClass, headMethod,
            static_cast<jlong>(reinterpret_cast<uintptr_t>(slots[slot].address)),
            static_cast<jlong>(reinterpret_cast<uintptr_t>(self)),
            static_cast<jlong>(a0), static_cast<jlong>(a1));
    if (clearJavaException(env)) return nullptr;
    return result;
}

void dispatchTail(JNIEnv *env, int slot, void *self, uintptr_t a0, uintptr_t a1) {
    if (!env || !tailMethod) return;
    env->CallStaticVoidMethod(bridgeClass, tailMethod,
            static_cast<jlong>(reinterpret_cast<uintptr_t>(slots[slot].address)),
            static_cast<jlong>(reinterpret_cast<uintptr_t>(self)),
            static_cast<jlong>(a0), static_cast<jlong>(a1));
    clearJavaException(env);
}

template<typename T> struct Boxed;
template<> struct Boxed<bool> {
    static constexpr const char *name = "java/lang/Boolean";
    static constexpr const char *method = "booleanValue";
    static constexpr const char *signature = "()Z";
    static bool read(JNIEnv *env, jobject obj, jmethodID id) {
        return env->CallBooleanMethod(obj, id) == JNI_TRUE;
    }
};
template<> struct Boxed<int> {
    static constexpr const char *name = "java/lang/Integer";
    static constexpr const char *method = "intValue";
    static constexpr const char *signature = "()I";
    static int read(JNIEnv *env, jobject obj, jmethodID id) {
        return env->CallIntMethod(obj, id);
    }
};
template<> struct Boxed<float> {
    static constexpr const char *name = "java/lang/Float";
    static constexpr const char *method = "floatValue";
    static constexpr const char *signature = "()F";
    static float read(JNIEnv *env, jobject obj, jmethodID id) {
        return env->CallFloatMethod(obj, id);
    }
};

template<typename T> bool readOverride(JNIEnv *env, jobject result, T &value) {
    if (!result) return false;
    if (!env) return false;
    bool cancelled = env->CallBooleanMethod(result, cancelledMethod) == JNI_TRUE;
    if (clearJavaException(env) || !cancelled) {
        env->DeleteLocalRef(result);
        return false;
    }
    jobject boxed = env->CallObjectMethod(result, valueMethod);
    if (clearJavaException(env) || !boxed) {
        env->DeleteLocalRef(result);
        return false;
    }
    jclass type = env->FindClass(Boxed<T>::name);
    if (clearJavaException(env) || !type || !env->IsInstanceOf(boxed, type)) {
        logError("Hook return override has the wrong boxed Java type");
        if (type) env->DeleteLocalRef(type);
        env->DeleteLocalRef(boxed);
        env->DeleteLocalRef(result);
        return false;
    }
    jmethodID unbox = env->GetMethodID(type, Boxed<T>::method, Boxed<T>::signature);
    if (clearJavaException(env) || !unbox) {
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(boxed);
        env->DeleteLocalRef(result);
        return false;
    }
    value = Boxed<T>::read(env, boxed, unbox);
    bool failed = clearJavaException(env);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(boxed);
    env->DeleteLocalRef(result);
    return !failed;
}

template<> bool readOverride<void *>(JNIEnv *env, jobject result, void *&value) {
    if (!result || !env) return false;
    bool cancelled = env->CallBooleanMethod(result, cancelledMethod) == JNI_TRUE;
    if (clearJavaException(env) || !cancelled) {
        env->DeleteLocalRef(result);
        return false;
    }
    jobject object = env->CallObjectMethod(result, valueMethod);
    if (clearJavaException(env)) {
        env->DeleteLocalRef(result);
        return false;
    }
    if (object && !env->IsInstanceOf(object, objectClass)) {
        logError("Object return override requires Il2CppObject or null");
        env->DeleteLocalRef(object);
        env->DeleteLocalRef(result);
        return false;
    }
    jlong address = object ? env->CallLongMethod(object, objectAddressMethod) : 0;
    bool failed = clearJavaException(env);
    if (object) env->DeleteLocalRef(object);
    env->DeleteLocalRef(result);
    if (!failed) value = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    return !failed;
}

template<int N, int A> void voidInvoke(void *self, uintptr_t a0, uintptr_t a1, const void *methodInfo) {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    jobject result = dispatchHead(env, N, self, a0, a1);
    if (result && env) env->DeleteLocalRef(result);
    if (A == 0) {
        auto original = reinterpret_cast<void (*)(void *, const void *)>(slots[N].original);
        if (original) original(self, methodInfo);
    } else if (A == 1) {
        auto original = reinterpret_cast<void (*)(void *, uintptr_t, const void *)>(slots[N].original);
        if (original) original(self, a0, methodInfo);
    } else {
        auto original = reinterpret_cast<void (*)(void *, uintptr_t, uintptr_t, const void *)>(slots[N].original);
        if (original) original(self, a0, a1, methodInfo);
    }
    dispatchTail(env, N, self, a0, a1);
}

template<int N, int A, typename T> T valueInvoke(void *self, uintptr_t a0, uintptr_t a1,
                                                const void *methodInfo) {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    T overrideValue{};
    if (readOverride(env, dispatchHead(env, N, self, a0, a1), overrideValue)) return overrideValue;
    if (A == 0) {
        auto original = reinterpret_cast<T (*)(void *, const void *)>(slots[N].original);
        return original ? original(self, methodInfo) : T{};
    }
    if (A == 1) {
        auto original = reinterpret_cast<T (*)(void *, uintptr_t, const void *)>(slots[N].original);
        return original ? original(self, a0, methodInfo) : T{};
    }
    auto original = reinterpret_cast<T (*)(void *, uintptr_t, uintptr_t, const void *)>(slots[N].original);
    return original ? original(self, a0, a1, methodInfo) : T{};
}

template<int N> void voidHook0(void *self, const void *methodInfo) {
    voidInvoke<N, 0>(self, 0, 0, methodInfo);
}
template<int N> void voidHook1(void *self, uintptr_t a0, const void *methodInfo) {
    voidInvoke<N, 1>(self, a0, 0, methodInfo);
}
template<int N> void voidHook2(void *self, uintptr_t a0, uintptr_t a1, const void *methodInfo) {
    voidInvoke<N, 2>(self, a0, a1, methodInfo);
}
template<int N, typename T> T valueHook0(void *self, const void *methodInfo) {
    return valueInvoke<N, 0, T>(self, 0, 0, methodInfo);
}
template<int N, typename T> T valueHook1(void *self, uintptr_t a0, const void *methodInfo) {
    return valueInvoke<N, 1, T>(self, a0, 0, methodInfo);
}
template<int N, typename T> T valueHook2(void *self, uintptr_t a0, uintptr_t a1, const void *methodInfo) {
    return valueInvoke<N, 2, T>(self, a0, a1, methodInfo);
}

template<int A, size_t... I> constexpr auto voidHooks(std::index_sequence<I...>) {
    if constexpr (A == 0) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&voidHook0<I>)...};
    if constexpr (A == 1) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&voidHook1<I>)...};
    return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&voidHook2<I>)...};
}
template<int A, typename T, size_t... I> constexpr auto valueHooks(std::index_sequence<I...>) {
    if constexpr (A == 0) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&valueHook0<I, T>)...};
    if constexpr (A == 1) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&valueHook1<I, T>)...};
    return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&valueHook2<I, T>)...};
}
const auto voidReplacements0 = voidHooks<0>(std::make_index_sequence<kSlots>{});
const auto voidReplacements1 = voidHooks<1>(std::make_index_sequence<kSlots>{});
const auto voidReplacements2 = voidHooks<2>(std::make_index_sequence<kSlots>{});
const auto boolReplacements0 = valueHooks<0, bool>(std::make_index_sequence<kSlots>{});
const auto boolReplacements1 = valueHooks<1, bool>(std::make_index_sequence<kSlots>{});
const auto boolReplacements2 = valueHooks<2, bool>(std::make_index_sequence<kSlots>{});
const auto intReplacements0 = valueHooks<0, int>(std::make_index_sequence<kSlots>{});
const auto intReplacements1 = valueHooks<1, int>(std::make_index_sequence<kSlots>{});
const auto intReplacements2 = valueHooks<2, int>(std::make_index_sequence<kSlots>{});
const auto floatReplacements0 = valueHooks<0, float>(std::make_index_sequence<kSlots>{});
const auto floatReplacements1 = valueHooks<1, float>(std::make_index_sequence<kSlots>{});
const auto floatReplacements2 = valueHooks<2, float>(std::make_index_sequence<kSlots>{});
const auto pointerReplacements0 = valueHooks<0, void *>(std::make_index_sequence<kSlots>{});
const auto pointerReplacements1 = valueHooks<1, void *>(std::make_index_sequence<kSlots>{});
const auto pointerReplacements2 = valueHooks<2, void *>(std::make_index_sequence<kSlots>{});

// Static method hooks: the IL2CPP ABI has no receiver, so the trampolines drop self and
// dispatch through the static Java entry points (no Il2CppObject receiver is built).
jobject dispatchHeadStatic(JNIEnv *env, int slot, uintptr_t a0, uintptr_t a1) {
    if (!env || !headStaticMethod) return nullptr;
    jobject result = env->CallStaticObjectMethod(bridgeClass, headStaticMethod,
            static_cast<jlong>(reinterpret_cast<uintptr_t>(slots[slot].address)),
            static_cast<jlong>(a0), static_cast<jlong>(a1));
    if (clearJavaException(env)) return nullptr;
    return result;
}
void dispatchTailStatic(JNIEnv *env, int slot, uintptr_t a0, uintptr_t a1) {
    if (!env || !tailStaticMethod) return;
    env->CallStaticVoidMethod(bridgeClass, tailStaticMethod,
            static_cast<jlong>(reinterpret_cast<uintptr_t>(slots[slot].address)),
            static_cast<jlong>(a0), static_cast<jlong>(a1));
    clearJavaException(env);
}

template<int N, int A> void staticVoidInvoke(uintptr_t a0, uintptr_t a1, const void *methodInfo) {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    jobject result = dispatchHeadStatic(env, N, a0, a1);
    if (result && env) env->DeleteLocalRef(result);
    if (A == 0) {
        auto original = reinterpret_cast<void (*)(const void *)>(slots[N].original);
        if (original) original(methodInfo);
    } else if (A == 1) {
        auto original = reinterpret_cast<void (*)(uintptr_t, const void *)>(slots[N].original);
        if (original) original(a0, methodInfo);
    } else {
        auto original = reinterpret_cast<void (*)(uintptr_t, uintptr_t, const void *)>(slots[N].original);
        if (original) original(a0, a1, methodInfo);
    }
    dispatchTailStatic(env, N, a0, a1);
}

template<int N, int A, typename T> T staticValueInvoke(uintptr_t a0, uintptr_t a1, const void *methodInfo) {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    T overrideValue{};
    if (readOverride(env, dispatchHeadStatic(env, N, a0, a1), overrideValue)) return overrideValue;
    if (A == 0) {
        auto original = reinterpret_cast<T (*)(const void *)>(slots[N].original);
        return original ? original(methodInfo) : T{};
    }
    if (A == 1) {
        auto original = reinterpret_cast<T (*)(uintptr_t, const void *)>(slots[N].original);
        return original ? original(a0, methodInfo) : T{};
    }
    auto original = reinterpret_cast<T (*)(uintptr_t, uintptr_t, const void *)>(slots[N].original);
    return original ? original(a0, a1, methodInfo) : T{};
}

template<int N> void staticVoidHook0(const void *methodInfo) { staticVoidInvoke<N, 0>(0, 0, methodInfo); }
template<int N> void staticVoidHook1(uintptr_t a0, const void *methodInfo) { staticVoidInvoke<N, 1>(a0, 0, methodInfo); }
template<int N> void staticVoidHook2(uintptr_t a0, uintptr_t a1, const void *methodInfo) { staticVoidInvoke<N, 2>(a0, a1, methodInfo); }
template<int N, typename T> T staticValueHook0(const void *methodInfo) { return staticValueInvoke<N, 0, T>(0, 0, methodInfo); }
template<int N, typename T> T staticValueHook1(uintptr_t a0, const void *methodInfo) { return staticValueInvoke<N, 1, T>(a0, 0, methodInfo); }
template<int N, typename T> T staticValueHook2(uintptr_t a0, uintptr_t a1, const void *methodInfo) { return staticValueInvoke<N, 2, T>(a0, a1, methodInfo); }

template<int A, size_t... I> constexpr auto staticVoidHooks(std::index_sequence<I...>) {
    if constexpr (A == 0) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticVoidHook0<I>)...};
    if constexpr (A == 1) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticVoidHook1<I>)...};
    return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticVoidHook2<I>)...};
}
template<int A, typename T, size_t... I> constexpr auto staticValueHooks(std::index_sequence<I...>) {
    if constexpr (A == 0) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticValueHook0<I, T>)...};
    if constexpr (A == 1) return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticValueHook1<I, T>)...};
    return std::array<void *, sizeof...(I)>{reinterpret_cast<void *>(&staticValueHook2<I, T>)...};
}
const auto sVoidReplacements0 = staticVoidHooks<0>(std::make_index_sequence<kSlots>{});
const auto sVoidReplacements1 = staticVoidHooks<1>(std::make_index_sequence<kSlots>{});
const auto sVoidReplacements2 = staticVoidHooks<2>(std::make_index_sequence<kSlots>{});
const auto sBoolReplacements0 = staticValueHooks<0, bool>(std::make_index_sequence<kSlots>{});
const auto sBoolReplacements1 = staticValueHooks<1, bool>(std::make_index_sequence<kSlots>{});
const auto sBoolReplacements2 = staticValueHooks<2, bool>(std::make_index_sequence<kSlots>{});
const auto sIntReplacements0 = staticValueHooks<0, int>(std::make_index_sequence<kSlots>{});
const auto sIntReplacements1 = staticValueHooks<1, int>(std::make_index_sequence<kSlots>{});
const auto sIntReplacements2 = staticValueHooks<2, int>(std::make_index_sequence<kSlots>{});
const auto sFloatReplacements0 = staticValueHooks<0, float>(std::make_index_sequence<kSlots>{});
const auto sFloatReplacements1 = staticValueHooks<1, float>(std::make_index_sequence<kSlots>{});
const auto sFloatReplacements2 = staticValueHooks<2, float>(std::make_index_sequence<kSlots>{});
const auto sPointerReplacements0 = staticValueHooks<0, void *>(std::make_index_sequence<kSlots>{});
const auto sPointerReplacements1 = staticValueHooks<1, void *>(std::make_index_sequence<kSlots>{});
const auto sPointerReplacements2 = staticValueHooks<2, void *>(std::make_index_sequence<kSlots>{});

// ABI tags: V void, Z bool, I 32-bit int, J 64-bit int, F float, D double, P object.
// Enums map to their underlying integer type; non-enum structs are '?' (unsupported).
char abiType(const BNM::IL2CPP::Il2CppType *type) {
    if (!type) return '?';
    using BNM::IL2CPP::Il2CppTypeEnum;
    switch (type->type) {
        case Il2CppTypeEnum::IL2CPP_TYPE_VOID: return 'V';
        case Il2CppTypeEnum::IL2CPP_TYPE_BOOLEAN: return 'Z';
        case Il2CppTypeEnum::IL2CPP_TYPE_I1:
        case Il2CppTypeEnum::IL2CPP_TYPE_U1:
        case Il2CppTypeEnum::IL2CPP_TYPE_I2:
        case Il2CppTypeEnum::IL2CPP_TYPE_U2:
        case Il2CppTypeEnum::IL2CPP_TYPE_CHAR:
        case Il2CppTypeEnum::IL2CPP_TYPE_I4:
        case Il2CppTypeEnum::IL2CPP_TYPE_U4: return 'I';
        case Il2CppTypeEnum::IL2CPP_TYPE_I8:
        case Il2CppTypeEnum::IL2CPP_TYPE_U8: return 'J';
        case Il2CppTypeEnum::IL2CPP_TYPE_R4: return 'F';
        case Il2CppTypeEnum::IL2CPP_TYPE_R8: return 'D';
        case Il2CppTypeEnum::IL2CPP_TYPE_PTR:
        case Il2CppTypeEnum::IL2CPP_TYPE_I:
        case Il2CppTypeEnum::IL2CPP_TYPE_U: return 'J';
        case Il2CppTypeEnum::IL2CPP_TYPE_CLASS:
        case Il2CppTypeEnum::IL2CPP_TYPE_OBJECT:
        case Il2CppTypeEnum::IL2CPP_TYPE_STRING:
        case Il2CppTypeEnum::IL2CPP_TYPE_ARRAY:
        case Il2CppTypeEnum::IL2CPP_TYPE_SZARRAY: return 'P';
        case Il2CppTypeEnum::IL2CPP_TYPE_VALUETYPE:
        case Il2CppTypeEnum::IL2CPP_TYPE_ENUM: {
            BNM::Class typeClass(type);
            auto *klass = typeClass.GetClass();
            if (klass && klass->enumtype && klass->element_class)
                return abiType(&klass->element_class->byval_arg);
            return 'S';   // value-type struct: boxed/unboxed for calls and fields
        }
        default: return '?';
    }
}

std::string canonicalType(const BNM::IL2CPP::Il2CppType *type) {
    BNM::Class typeClass(type);
    auto *klass = typeClass.GetClass();
    if (!klass || !klass->name) return "?";
    std::string name = klass->name;
    std::string ns = klass->namespaze ? klass->namespaze : "";
    if (ns == "System") {
        if (name == "Void") return "void";
        if (name == "Boolean") return "boolean";
        if (name == "Int32") return "int";
        if (name == "Single") return "float";
        if (name == "Int64") return "long";
        if (name == "Double") return "double";
        if (name == "String") return "string";
    }
    return ns.empty() ? name : ns + "." + name;
}

#if defined(__aarch64__) || defined(__arm__)
// One logical argument's location in the saved register context.
struct ArgLoc {
    char abi;          // I J Z F D P S
    bool fp;           // true = FP register (q), false = GP register (x)
    bool stack;        // passed on the stack (register classes exhausted)
    int reg;           // first register index (when !stack)
    int nregs;         // registers consumed (structs span several)
    int stackOffset;   // byte offset into the copied stack area (when stack)
    const BNM::IL2CPP::Il2CppClass *structClass;  // for 'S'
    bool structByRef;  // large struct passed as a pointer
};
struct UniversalSlot {
    long address = 0;
    bool used = false;
    bool instance = false;
    char retAbi = 'V';
    const BNM::IL2CPP::Il2CppClass *retStructClass = nullptr;
    int stackBytes = 0;    // total bytes of stack-spilled arguments (<= 0x80)
    int selfReg = 0;       // register index holding `this` (shifts for arm32 indirect returns)
    std::vector<ArgLoc> args;
};
UniversalSlot g_uslots[kSlots];
int g_universalSlots = 0;

#if defined(__aarch64__)
// Is this value type a homogeneous float aggregate (<=4 float or <=4 double fields)?
// Returns the member count via `count` and sets `isDouble`; 0 if not an HFA.
int hfaInfo(const BNM::IL2CPP::Il2CppClass *klass, bool &isDouble) {
    if (!klass) return 0;
    BNM::Class c(klass);
    int count = 0; char base = 0;
    for (const auto &f : c.GetFields(false)) {
        auto *fi = f.GetInfo();
        if (!fi || !fi->type || (fi->type->attrs & 0x10)) continue;   // skip static
        char a = abiType(fi->type);
        if (a != 'F' && a != 'D') return 0;
        if (base == 0) base = a; else if (base != a) return 0;
        if (++count > 4) return 0;
    }
    if (count == 0) return 0;
    isDouble = base == 'D';
    return count;
}

// AArch64 argument -> register / stack assignment (scalars + float HFA + small/large
// structs, with stack spill when a register class is exhausted).
bool classifyUniversal(UniversalSlot &s, const BNM::IL2CPP::MethodInfo *info, bool instance) {
    s.args.clear();
    s.instance = instance;
    int ngrn = instance ? 1 : 0, nsrn = 0, nsaa = 0;   // nsaa = next stacked-argument offset
    auto spill = [&](ArgLoc &loc, size_t size) {       // place an argument on the stack
        size_t aligned = (size + 7) & ~size_t(7);
        loc.stack = true; loc.stackOffset = nsaa; nsaa += (int) aligned;
    };
    for (int i = 0; i < info->parameters_count; ++i) {
        char a = abiType(info->parameters[i]);
        ArgLoc loc{a, false, false, 0, 1, 0, nullptr, false};
        if (a == 'F' || a == 'D') {
            if (nsrn < 8) loc.fp = true, loc.reg = nsrn++; else spill(loc, 8);
        } else if (a == 'I' || a == 'J' || a == 'Z' || a == 'P') {
            if (ngrn < 8) loc.reg = ngrn++; else spill(loc, 8);
        } else if (a == 'S') {
            BNM::Class sc(info->parameters[i]);
            auto *klass = sc.GetClass();
            if (!klass) return false;
            loc.structClass = klass;
            bool isDouble = false; int hfa = hfaInfo(klass, isDouble);
            size_t size = klass->instance_size - kObjectHeader;
            if (hfa) {
                if (nsrn + hfa <= 8) { loc.fp = true; loc.reg = nsrn; loc.nregs = hfa; nsrn += hfa; }
                else { nsrn = 8; spill(loc, size); }
            } else if (size <= 16) {
                int need = (int) ((size + 7) / 8);
                if (ngrn + need <= 8) { loc.reg = ngrn; loc.nregs = need; ngrn += need; }
                else { ngrn = 8; spill(loc, size); }
            } else {
                loc.structByRef = true;   // large struct passed as a pointer
                if (ngrn < 8) loc.reg = ngrn++; else spill(loc, 8);
            }
        } else return false;   // unsupported type
        s.args.push_back(loc);
    }
    if (nsaa > 0x80) return false;   // more stacked arguments than the trampoline copies
    s.stackBytes = (nsaa + 15) & ~15; // keep the copy area 16-aligned
    s.retAbi = abiType(info->return_type);
    if (s.retAbi == '?') return false;
    if (s.retAbi == 'S') { BNM::Class rc(info->return_type); s.retStructClass = rc.GetClass(); }
    return true;
}
#elif defined(__arm__)
// ARM (armeabi-v7a, softfp) argument assignment: every argument class (including float,
// double and structs) is passed in r0-r3 then on the stack. Doubles/longs take an aligned
// register pair; structs are passed by value (copied), large ones by a pointer; a struct
// return larger than 4 bytes is indirect (result pointer in r0, arguments shift).
bool classifyUniversal(UniversalSlot &s, const BNM::IL2CPP::MethodInfo *info, bool instance) {
    s.args.clear();
    s.instance = instance;
    s.retAbi = abiType(info->return_type);
    if (s.retAbi == '?') return false;
    size_t retSize = 0;
    if (s.retAbi == 'S') {
        BNM::Class rc(info->return_type);
        s.retStructClass = rc.GetClass();
        if (!s.retStructClass) return false;
        retSize = s.retStructClass->instance_size - kObjectHeader;
    }
    // For an indirect struct return (>4 bytes) the hidden result pointer takes r0, so `this`
    // (and the arguments) shift up by one core register.
    bool indirect = (s.retAbi == 'S' && retSize > 4);
    int ncrn = indirect ? 1 : 0, nsaa = 0;
    s.selfReg = ncrn;
    if (instance) ncrn++;
    auto spill = [&](ArgLoc &loc, size_t size, bool align8) {
        if (align8) nsaa = (nsaa + 7) & ~7;
        loc.stack = true; loc.stackOffset = nsaa; nsaa += (int) ((size + 3) & ~size_t(3));
    };
    for (int i = 0; i < info->parameters_count; ++i) {
        char a = abiType(info->parameters[i]);
        ArgLoc loc{a, false, false, 0, 1, 0, nullptr, false};
        size_t size;
        if (a == 'J' || a == 'D') size = 8;
        else if (a == 'I' || a == 'F' || a == 'Z' || a == 'P') size = 4;
        else if (a == 'S') {
            BNM::Class sc(info->parameters[i]);
            auto *klass = sc.GetClass();
            if (!klass) return false;
            loc.structClass = klass;
            size_t ssize = klass->instance_size - kObjectHeader;
            if (ssize > 16) { loc.structByRef = true; size = 4; }   // large struct by pointer
            else size = ssize;
        } else return false;
        int need = (int) ((size + 3) / 4);
        bool align8 = (size == 8);
        if (align8 && (ncrn & 1)) ncrn++;              // 8-byte types start on an even register
        if (ncrn + need <= 4) { loc.reg = ncrn; loc.nregs = need; ncrn += need; }
        else { ncrn = 4; spill(loc, size, align8); }
        s.args.push_back(loc);
    }
    if (nsaa > 0x80) return false;
    s.stackBytes = (nsaa + 3) & ~3;
    return true;
}
#endif
#endif

void onBnmReady() {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    if (!env || !onReadyMethod) return;
    env->CallStaticVoidMethod(bridgeClass, onReadyMethod);
    clearJavaException(env);
}

std::string jstr(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

// Accessor plumbing. self == nullptr means a static member. Values cross JNI as
// jlong: int sign-extended, float in the low 32 bits, boolean 0/1, object address.
float bitsToFloat(uint32_t bits) { float value; std::memcpy(&value, &bits, sizeof(value)); return value; }
uint32_t floatToBits(float value) { uint32_t bits; std::memcpy(&bits, &value, sizeof(bits)); return bits; }
double bitsToDouble(uint64_t bits) { double value; std::memcpy(&value, &bits, sizeof(value)); return value; }
uint64_t doubleToBits(double value) { uint64_t bits; std::memcpy(&bits, &value, sizeof(bits)); return bits; }

// Box a value-type's raw data into a new heap object (copies the bytes); 0 on failure.
using BoxFn = BNM::IL2CPP::Il2CppObject *(*)(const BNM::IL2CPP::Il2CppClass *, void *);
BoxFn g_valueBox = nullptr;
jlong boxStruct(BNM::Class type, const void *data) {
    auto *klass = type.GetClass();
    if (!klass || !data) return 0;
    if (!g_valueBox) g_valueBox = reinterpret_cast<BoxFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_value_box"));
    if (!g_valueBox) { logError("il2cpp_value_box unavailable"); return 0; }
    return (jlong) reinterpret_cast<uintptr_t>(g_valueBox(klass, const_cast<void *>(data)));
}

// GC handles let a mod keep an IL2CPP object past the callback. A weak handle tracks
// liveness without keeping the object alive: get_target returns the current pointer, or
// null once the game has dropped it and the GC collected it.
using GcWeakNewFn = uint32_t (*)(void *, int32_t);
using GcTargetFn = void *(*)(uint32_t);
using GcFreeFn = void (*)(uint32_t);
GcWeakNewFn g_gcWeakNew = nullptr;
GcTargetFn g_gcTarget = nullptr;
GcFreeFn g_gcFree = nullptr;
bool resolveGc() {
    if (!g_gcWeakNew) g_gcWeakNew = reinterpret_cast<GcWeakNewFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_gchandle_new_weakref"));
    if (!g_gcTarget) g_gcTarget = reinterpret_cast<GcTargetFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_gchandle_get_target"));
    if (!g_gcFree) g_gcFree = reinterpret_cast<GcFreeFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_gchandle_free"));
    if (g_gcWeakNew && g_gcTarget && g_gcFree) return true;
    logError("il2cpp_gchandle_* unavailable");
    return false;
}

jlong fieldGet(void *self, BNM::FieldBase base, jchar abi) {
    // A field declared inside a value type is accessed through a boxed object; il2cpp's
    // field offset is header-relative, so read it raw (BNM's Field path validates the
    // instance as an object and would reject the boxed payload).
    auto *fi = base.GetInfo();
    if (self && base._isInStruct && fi) {
        void *p = reinterpret_cast<char *>(self) + fi->offset;
        switch (abi) {
            case 'I': return *reinterpret_cast<int32_t *>(p);
            case 'J': return *reinterpret_cast<int64_t *>(p);
            case 'Z': return *reinterpret_cast<bool *>(p) ? 1 : 0;
            case 'F': return (jlong) floatToBits(*reinterpret_cast<float *>(p));
            case 'D': return (jlong) doubleToBits(*reinterpret_cast<double *>(p));
            case 'P': return abiType(fi->type) == 'S' ? boxStruct(base.GetType(), p)
                                                      : (jlong) reinterpret_cast<uintptr_t>(*reinterpret_cast<void **>(p));
            default: logError("Unsupported field ABI"); return 0;
        }
    }
    auto *instance = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(self);
    switch (abi) {
        case 'I': { auto f = base.cast<int32_t>(); if (self) f.SetInstance(instance); return (jlong) f.Get(); }
        case 'J': { auto f = base.cast<int64_t>(); if (self) f.SetInstance(instance); return (jlong) f.Get(); }
        case 'Z': { auto f = base.cast<bool>(); if (self) f.SetInstance(instance); return f.Get() ? 1 : 0; }
        case 'F': { auto f = base.cast<float>(); if (self) f.SetInstance(instance); return (jlong) floatToBits(f.Get()); }
        case 'D': { auto f = base.cast<double>(); if (self) f.SetInstance(instance); return (jlong) doubleToBits(f.Get()); }
        case 'P': {
            // Object references pass the pointer; value-type fields are boxed into a copy.
            if (base.GetInfo() && abiType(base.GetInfo()->type) == 'S') {
                if (self) base.SetInstance(instance);
                return boxStruct(base.GetType(), base.GetFieldPointer());
            }
            auto f = base.cast<void *>(); if (self) f.SetInstance(instance);
            return (jlong) reinterpret_cast<uintptr_t>(f.Get());
        }
        default: logError("Unsupported field ABI"); return 0;
    }
}

void fieldSet(void *self, BNM::FieldBase base, jchar abi, jlong value) {
    // Field inside a value type (boxed object): write raw at the header-relative offset.
    auto *fi = base.GetInfo();
    if (self && base._isInStruct && fi) {
        void *p = reinterpret_cast<char *>(self) + fi->offset;
        switch (abi) {
            case 'I': *reinterpret_cast<int32_t *>(p) = (int32_t) value; return;
            case 'J': *reinterpret_cast<int64_t *>(p) = (int64_t) value; return;
            case 'Z': *reinterpret_cast<bool *>(p) = value != 0; return;
            case 'F': *reinterpret_cast<float *>(p) = bitsToFloat((uint32_t) value); return;
            case 'D': *reinterpret_cast<double *>(p) = bitsToDouble((uint64_t) value); return;
            case 'P':
                if (abiType(fi->type) == 'S') {
                    auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(value));
                    auto *klass = base.GetType().GetClass();
                    if (boxed && klass)
                        std::memcpy(p, reinterpret_cast<char *>(boxed) + kObjectHeader, klass->instance_size - kObjectHeader);
                } else {
                    *reinterpret_cast<void **>(p) = reinterpret_cast<void *>(static_cast<uintptr_t>(value));
                }
                return;
            default: logError("Unsupported field ABI"); return;
        }
    }
    auto *instance = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(self);
    switch (abi) {
        case 'I': { auto f = base.cast<int32_t>(); if (self) f.SetInstance(instance); f.Set((int32_t) value); break; }
        case 'J': { auto f = base.cast<int64_t>(); if (self) f.SetInstance(instance); f.Set((int64_t) value); break; }
        case 'Z': { auto f = base.cast<bool>(); if (self) f.SetInstance(instance); f.Set(value != 0); break; }
        case 'F': { auto f = base.cast<float>(); if (self) f.SetInstance(instance);
                    f.Set(bitsToFloat((uint32_t) value)); break; }
        case 'D': { auto f = base.cast<double>(); if (self) f.SetInstance(instance);
                    f.Set(bitsToDouble((uint64_t) value)); break; }
        case 'P': {
            if (base.GetInfo() && abiType(base.GetInfo()->type) == 'S') {
                // value-type field: copy the boxed object's payload into the field storage
                if (self) base.SetInstance(instance);
                void *dst = base.GetFieldPointer();
                auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(value));
                auto *klass = base.GetType().GetClass();
                if (dst && boxed && klass)
                    std::memcpy(dst, reinterpret_cast<char *>(boxed) + kObjectHeader, klass->instance_size - kObjectHeader);
                break;
            }
            auto f = base.cast<void *>(); if (self) f.SetInstance(instance);
            f.Set(reinterpret_cast<void *>(static_cast<uintptr_t>(value))); break;
        }
        default: logError("Unsupported field ABI");
    }
}

jlong invokeMethod(void *self, const BNM::MethodBase &method, jchar returnAbi,
                   const std::string &argAbi, const jlong *args) {
    auto *info = method.GetInfo();
    if (!info || !info->methodPointer || !info->invoker_method) {
        logError("Target IL2CPP method is not callable");
        return 0;
    }
    // Argument marshalling is driven by the method's real parameter types, so value-type
    // (struct) arguments are unboxed from the passed object transparently.
    size_t count = argAbi.size();
    if (count != (size_t) info->parameters_count) {
        logError("invoke argument count does not match the target method");
        return 0;
    }
    std::vector<uint64_t> storage(count ? count : 1, 0);
    std::vector<void *> argv(count ? count : 1, nullptr);
    for (size_t i = 0; i < count; ++i) {
        char a = abiType(info->parameters[i]);
        switch (a) {
            case 'P': argv[i] = reinterpret_cast<void *>(static_cast<uintptr_t>(args[i])); break;
            case 'S': {   // value type: the caller passes a boxed object; unbox to its payload
                auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(args[i]));
                argv[i] = boxed ? reinterpret_cast<char *>(boxed) + kObjectHeader : nullptr;
                break;
            }
            case 'I': *reinterpret_cast<int32_t *>(&storage[i]) = (int32_t) args[i]; argv[i] = &storage[i]; break;
            case 'J': *reinterpret_cast<int64_t *>(&storage[i]) = (int64_t) args[i]; argv[i] = &storage[i]; break;
            case 'Z': *reinterpret_cast<bool *>(&storage[i]) = args[i] != 0; argv[i] = &storage[i]; break;
            case 'F': *reinterpret_cast<float *>(&storage[i]) = bitsToFloat((uint32_t) args[i]); argv[i] = &storage[i]; break;
            case 'D': *reinterpret_cast<double *>(&storage[i]) = bitsToDouble((uint64_t) args[i]); argv[i] = &storage[i]; break;
            default: logError("Unsupported argument type in invoke"); return 0;
        }
    }
    // Size the return buffer to the real return type so struct returns are not truncated.
    char retActual = abiType(info->return_type);
    BNM::Class retClass(info->return_type);
    size_t retSize = 16;
    if (retActual == 'S' && retClass.GetClass())
        retSize = std::max<size_t>(16, retClass.GetClass()->instance_size - kObjectHeader);
    std::vector<uint8_t> ret(retSize, 0);
    info->invoker_method(info->methodPointer, info, self, argv.data(), ret.data());
    if (retActual == 'S') return boxStruct(retClass, ret.data());   // box the returned struct
    switch (returnAbi) {
        case 'V': return 0;
        case 'I': return (jlong) *reinterpret_cast<int32_t *>(ret.data());
        case 'J': return (jlong) *reinterpret_cast<int64_t *>(ret.data());
        case 'Z': return *reinterpret_cast<bool *>(ret.data()) ? 1 : 0;
        case 'F': return (jlong) floatToBits(*reinterpret_cast<float *>(ret.data()));
        case 'D': return (jlong) doubleToBits(*reinterpret_cast<double *>(ret.data()));
        case 'P': return (jlong) reinterpret_cast<uintptr_t>(*reinterpret_cast<void **>(ret.data()));
        default: logError("Unsupported return ABI"); return 0;
    }
}
}

#if defined(__aarch64__) || defined(__arm__)
static jlong boxStructFromBytes(const BNM::IL2CPP::Il2CppClass *klass, const void *data) {
    if (!g_valueBox) g_valueBox = reinterpret_cast<BoxFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_value_box"));
    if (!g_valueBox || !klass || !data) return 0;
    return (jlong) reinterpret_cast<uintptr_t>(g_valueBox(klass, const_cast<void *>(data)));
}

#if defined(__aarch64__)
// Read one argument from the saved register context, bit-encoded into a jlong (float/double
// as bits, boolean 0/1, object/boxed-struct as address).
static jlong readArgRaw(const ArgLoc &loc, TrampolineCtx *ctx) {
    if (loc.stack) {
        const uint8_t *p = reinterpret_cast<const uint8_t *>(ctx->stackCopy) + loc.stackOffset;
        switch (loc.abi) {
            case 'I': return (jlong) *reinterpret_cast<const int32_t *>(p);
            case 'J': return (jlong) *reinterpret_cast<const int64_t *>(p);
            case 'Z': return (jlong) (*p & 1);
            case 'P': return (jlong) *reinterpret_cast<const uintptr_t *>(p);
            case 'F': return (jlong) *reinterpret_cast<const uint32_t *>(p);
            case 'D': return (jlong) *reinterpret_cast<const uint64_t *>(p);
            case 'S': {
                const auto *k = loc.structClass;
                if (!k) return 0;
                if (loc.structByRef) { void *q = *reinterpret_cast<void *const *>(p); return q ? boxStructFromBytes(k, q) : 0; }
                return boxStructFromBytes(k, p);   // struct bytes sit inline on the stack
            }
            default: return 0;
        }
    }
    switch (loc.abi) {
        case 'I': return (jlong) (int32_t) ctx->gp[loc.reg];
        case 'J': return (jlong) ctx->gp[loc.reg];
        case 'Z': return (jlong) (ctx->gp[loc.reg] & 1);
        case 'P': return (jlong) ctx->gp[loc.reg];
        case 'F': return (jlong) (uint32_t) ctx->fp[loc.reg];
        case 'D': return (jlong) (uint64_t) ctx->fp[loc.reg];
        case 'S': {
            const auto *k = loc.structClass;
            if (!k) return 0;
            size_t size = k->instance_size - kObjectHeader;
            if (loc.structByRef) {
                void *p = reinterpret_cast<void *>(ctx->gp[loc.reg]);
                return p ? boxStructFromBytes(k, p) : 0;
            }
            std::vector<uint8_t> buf(size ? size : 1, 0);
            if (loc.fp) {
                bool isDouble = false; hfaInfo(k, isDouble);
                size_t es = isDouble ? 8 : 4;
                for (int i = 0; i < loc.nregs; ++i)
                    std::memcpy(buf.data() + i * es, &ctx->fp[loc.reg + i], std::min(es, size - i * es));
            } else {
                for (int i = 0; i < loc.nregs; ++i) {
                    uint64_t v = ctx->gp[loc.reg + i];
                    std::memcpy(buf.data() + i * 8, &v, std::min<size_t>(8, size - i * 8));
                }
            }
            return boxStructFromBytes(k, buf.data());
        }
        default: return 0;
    }
}

// Write a (possibly modified) argument value back into the register context before the
// original method runs, so callbacks can change what the method receives.
static void writeArgRaw(const ArgLoc &loc, TrampolineCtx *ctx, jlong value) {
    if (loc.stack) {
        uint8_t *p = reinterpret_cast<uint8_t *>(ctx->stackCopy) + loc.stackOffset;
        switch (loc.abi) {
            case 'I': *reinterpret_cast<int32_t *>(p) = (int32_t) value; return;
            case 'J': *reinterpret_cast<int64_t *>(p) = (int64_t) value; return;
            case 'Z': *p = (uint8_t) (value & 1); return;
            case 'P': *reinterpret_cast<uintptr_t *>(p) = (uintptr_t) value; return;
            case 'F': *reinterpret_cast<uint32_t *>(p) = (uint32_t) value; return;
            case 'D': *reinterpret_cast<uint64_t *>(p) = (uint64_t) value; return;
            case 'S': {
                const auto *k = loc.structClass;
                auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(value));
                if (!k || !boxed) return;
                const uint8_t *src = reinterpret_cast<const uint8_t *>(boxed) + kObjectHeader;
                size_t size = k->instance_size - kObjectHeader;
                if (loc.structByRef) { void *q = *reinterpret_cast<void **>(p); if (q) std::memcpy(q, src, size); }
                else std::memcpy(p, src, size);
                return;
            }
            default: return;
        }
    }
    switch (loc.abi) {
        case 'I': ctx->gp[loc.reg] = (uint64_t) (int64_t) (int32_t) value; break;
        case 'J': ctx->gp[loc.reg] = (uint64_t) value; break;
        case 'Z': ctx->gp[loc.reg] = value & 1; break;
        case 'P': ctx->gp[loc.reg] = (uint64_t) value; break;
        case 'F': ctx->fp[loc.reg] = (__int128_t) (uint32_t) value; break;
        case 'D': ctx->fp[loc.reg] = (__int128_t) (uint64_t) value; break;
        case 'S': {
            const auto *k = loc.structClass;
            auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(value));
            if (!k || !boxed) break;
            const uint8_t *src = reinterpret_cast<const uint8_t *>(boxed) + kObjectHeader;
            size_t size = k->instance_size - kObjectHeader;
            if (loc.structByRef) {
                void *p = reinterpret_cast<void *>(ctx->gp[loc.reg]);
                if (p) std::memcpy(p, src, size);
            } else if (loc.fp) {
                bool isDouble = false; hfaInfo(k, isDouble);
                size_t es = isDouble ? 8 : 4;
                for (int i = 0; i < loc.nregs; ++i)
                    std::memcpy(&ctx->fp[loc.reg + i], src + i * es, std::min(es, size - i * es));
            } else {
                for (int i = 0; i < loc.nregs; ++i)
                    std::memcpy(&ctx->gp[loc.reg + i], src + i * 8, std::min<size_t>(8, size - i * 8));
            }
            break;
        }
        default: break;
    }
}

// Decode a cancelled callback's override value into the return registers.
static void writeReturn(JNIEnv *env, const UniversalSlot &s, jobject val, TrampolineCtx *ctx) {
    switch (s.retAbi) {
        case 'I': ctx->ret_gp[0] = val ? (uint64_t) (int64_t) env->CallIntMethod(val, unboxIntValue) : 0; break;
        case 'J': ctx->ret_gp[0] = val ? (uint64_t) env->CallLongMethod(val, unboxLongValue) : 0; break;
        case 'Z': ctx->ret_gp[0] = (val && env->CallBooleanMethod(val, unboxBoolValue) == JNI_TRUE) ? 1 : 0; break;
        case 'P': ctx->ret_gp[0] = val ? (uint64_t) env->CallLongMethod(val, objectAddressMethod) : 0; break;
        case 'F': { float f = val ? env->CallFloatMethod(val, unboxFloatValue) : 0.0f;
                    ctx->ret_fp[0] = (__int128_t) floatToBits(f); break; }
        case 'D': { double d = val ? env->CallDoubleMethod(val, unboxDoubleValue) : 0.0;
                    ctx->ret_fp[0] = (__int128_t) doubleToBits(d); break; }
        case 'S': {
            // Override a value-type return: place the boxed struct's payload where the
            // caller expects it — HFA in q0..q3, <=16B in x0/x1, larger via the x8 buffer.
            if (!val || !s.retStructClass) break;
            auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(
                    static_cast<uintptr_t>(env->CallLongMethod(val, objectAddressMethod)));
            if (clearJavaException(env) || !boxed) break;
            const uint8_t *src = reinterpret_cast<const uint8_t *>(boxed) + kObjectHeader;
            size_t size = s.retStructClass->instance_size - kObjectHeader;
            bool isDouble = false; int hfa = hfaInfo(s.retStructClass, isDouble);
            if (hfa) {
                size_t es = isDouble ? 8 : 4;
                for (int i = 0; i < hfa; ++i) std::memcpy(&ctx->ret_fp[i], src + i * es, std::min(es, size - i * es));
            } else if (size <= 16) {
                std::memcpy(&ctx->ret_gp[0], src, size);
            } else {
                void *buffer = reinterpret_cast<void *>(ctx->gp[8]);   // x8 indirect result
                if (buffer) { std::memcpy(buffer, src, size); ctx->ret_gp[0] = (uint64_t) buffer; }
            }
            break;
        }
        default: break;
    }
}
#elif defined(__arm__)
// ARM softfp: every argument is in r0-r3 or on the stack; register pairs and stacked bytes
// are contiguous, so one byte pointer reads/writes each argument.
static const uint8_t *argPtr(const ArgLoc &loc, const TrampolineCtx *ctx) {
    return loc.stack ? reinterpret_cast<const uint8_t *>(ctx->stackCopy) + loc.stackOffset
                     : reinterpret_cast<const uint8_t *>(&ctx->gp[loc.reg]);
}

static jlong readArgRaw(const ArgLoc &loc, TrampolineCtx *ctx) {
    const uint8_t *p = argPtr(loc, ctx);
    switch (loc.abi) {
        case 'I': return (jlong) *reinterpret_cast<const int32_t *>(p);
        case 'Z': return (jlong) (*p & 1);
        case 'P': case 'F': return (jlong) *reinterpret_cast<const uint32_t *>(p);
        case 'J': case 'D': return (jlong) *reinterpret_cast<const int64_t *>(p);
        case 'S': {
            const auto *k = loc.structClass;
            if (!k) return 0;
            if (loc.structByRef) { void *q = *reinterpret_cast<void *const *>(p); return q ? boxStructFromBytes(k, q) : 0; }
            return boxStructFromBytes(k, p);
        }
        default: return 0;
    }
}

static void writeArgRaw(const ArgLoc &loc, TrampolineCtx *ctx, jlong value) {
    uint8_t *p = const_cast<uint8_t *>(argPtr(loc, ctx));
    switch (loc.abi) {
        case 'I': *reinterpret_cast<int32_t *>(p) = (int32_t) value; return;
        case 'Z': *p = (uint8_t) (value & 1); return;
        case 'P': case 'F': *reinterpret_cast<uint32_t *>(p) = (uint32_t) value; return;
        case 'J': case 'D': *reinterpret_cast<int64_t *>(p) = (int64_t) value; return;
        case 'S': {
            const auto *k = loc.structClass;
            auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(static_cast<uintptr_t>(value));
            if (!k || !boxed) return;
            const uint8_t *src = reinterpret_cast<const uint8_t *>(boxed) + kObjectHeader;
            size_t size = k->instance_size - kObjectHeader;
            if (loc.structByRef) { void *q = *reinterpret_cast<void **>(p); if (q) std::memcpy(q, src, size); }
            else std::memcpy(p, src, size);
            return;
        }
        default: return;
    }
}

static void writeReturn(JNIEnv *env, const UniversalSlot &s, jobject val, TrampolineCtx *ctx) {
    switch (s.retAbi) {
        case 'I': ctx->ret[0] = val ? (uint32_t) env->CallIntMethod(val, unboxIntValue) : 0; break;
        case 'Z': ctx->ret[0] = (val && env->CallBooleanMethod(val, unboxBoolValue) == JNI_TRUE) ? 1 : 0; break;
        case 'P': ctx->ret[0] = val ? (uint32_t) env->CallLongMethod(val, objectAddressMethod) : 0; break;
        case 'F': { float f = val ? env->CallFloatMethod(val, unboxFloatValue) : 0.0f; ctx->ret[0] = floatToBits(f); break; }
        case 'J': { int64_t v = val ? env->CallLongMethod(val, unboxLongValue) : 0;
                    ctx->ret[0] = (uint32_t) v; ctx->ret[1] = (uint32_t) ((uint64_t) v >> 32); break; }
        case 'D': { double d = val ? env->CallDoubleMethod(val, unboxDoubleValue) : 0.0; uint64_t b = doubleToBits(d);
                    ctx->ret[0] = (uint32_t) b; ctx->ret[1] = (uint32_t) (b >> 32); break; }
        case 'S': {
            if (!val || !s.retStructClass) break;
            auto *boxed = reinterpret_cast<BNM::IL2CPP::Il2CppObject *>(
                    static_cast<uintptr_t>(env->CallLongMethod(val, objectAddressMethod)));
            if (clearJavaException(env) || !boxed) break;
            const uint8_t *src = reinterpret_cast<const uint8_t *>(boxed) + kObjectHeader;
            size_t size = s.retStructClass->instance_size - kObjectHeader;
            if (size <= 4) std::memcpy(&ctx->ret[0], src, size);
            else { void *buf = reinterpret_cast<void *>(ctx->gp[0]); if (buf) { std::memcpy(buf, src, size); ctx->ret[0] = (uint32_t) reinterpret_cast<uintptr_t>(buf); } }
            break;
        }
        default: break;
    }
}

// Looked up by the arm32 trampoline: origin pointer (low 32 bits) + stack byte count (high).
extern "C" uint64_t frostbite_prepare32(uint32_t slot) {
    if (slot >= (uint32_t) kSlots) return 0;
    uint32_t origin = (uint32_t) reinterpret_cast<uintptr_t>(frostbite_origin_table[slot]);
    return (uint64_t) origin | ((uint64_t) frostbite_slot_stackbytes[slot] << 32);
}
#endif

// Pack the arguments into a Java long[] (bit-encoded) plus a type-tag string. Java builds
// the typed callback parameters (or an Arguments view) from these.
static jlongArray packArgs(JNIEnv *env, const UniversalSlot &s, TrampolineCtx *ctx, std::string &abi) {
    int n = (int) s.args.size();
    std::vector<jlong> vals(n > 0 ? n : 1);
    abi.clear();
    for (int i = 0; i < n; ++i) { abi += s.args[i].abi; vals[i] = readArgRaw(s.args[i], ctx); }
    jlongArray arr = env->NewLongArray(n);
    if (arr && n) env->SetLongArrayRegion(arr, 0, n, vals.data());
    return arr;
}

extern "C" int frostbite_dispatch_head(uint64_t slot, TrampolineCtx *ctx) {
    if (slot >= (uint64_t) kSlots || !g_uslots[slot].used) return 0;
    const UniversalSlot &s = g_uslots[slot];
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    if (!env) return 0;
    std::string abi;
    jlongArray arr = packArgs(env, s, ctx, abi);
    if (!arr) { clearJavaException(env); return 0; }
    jstring jabi = env->NewStringUTF(abi.c_str());
    jlong self = s.instance ? (jlong) ctx->gp[s.selfReg] : 0;
    jobject cir = env->CallStaticObjectMethod(bridgeClass, headUniversalMethod, (jlong) s.address, self, arr, jabi);
    bool failed = clearJavaException(env);
    // Write any modified arguments back into the registers before the original runs.
    int n = (int) s.args.size();
    if (!failed && n) {
        std::vector<jlong> vals(n);
        env->GetLongArrayRegion(arr, 0, n, vals.data());
        for (int i = 0; i < n; ++i) writeArgRaw(s.args[i], ctx, vals[i]);
    }
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(jabi);
    if (failed || !cir) return 0;
    bool cancelled = env->CallBooleanMethod(cir, cancelledMethod) == JNI_TRUE;
    if (clearJavaException(env) || !cancelled) { env->DeleteLocalRef(cir); return 0; }
    jobject val = env->CallObjectMethod(cir, valueMethod);
    if (!clearJavaException(env)) writeReturn(env, s, val, ctx);
    if (val) env->DeleteLocalRef(val);
    env->DeleteLocalRef(cir);
    return 1;   // cancel: skip the original, return the override
}

extern "C" void frostbite_dispatch_tail(uint64_t slot, TrampolineCtx *ctx) {
    if (slot >= (uint64_t) kSlots || !g_uslots[slot].used) return;
    const UniversalSlot &s = g_uslots[slot];
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    if (!env) return;
    std::string abi;
    jlongArray arr = packArgs(env, s, ctx, abi);
    if (!arr) { clearJavaException(env); return; }
    jstring jabi = env->NewStringUTF(abi.c_str());
    jlong self = s.instance ? (jlong) ctx->gp[s.selfReg] : 0;
    env->CallStaticVoidMethod(bridgeClass, tailUniversalMethod, (jlong) s.address, self, arr, jabi);
    clearJavaException(env);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(jabi);
}
#endif

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *javaVm, void *) {
    vm = javaVm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass localBridge = env->FindClass("dev/frostbite/runtime/NativeBridge");
    jclass localCallback = env->FindClass("dev/frostbite/api/CallbackInfoReturnable");
    jclass localObject = env->FindClass("dev/frostbite/api/Il2CppObject");
    if (!localBridge || !localCallback || !localObject) return JNI_ERR;
    bridgeClass = static_cast<jclass>(env->NewGlobalRef(localBridge));
    callbackClass = static_cast<jclass>(env->NewGlobalRef(localCallback));
    objectClass = static_cast<jclass>(env->NewGlobalRef(localObject));
    env->DeleteLocalRef(localBridge);
    env->DeleteLocalRef(localCallback);
    env->DeleteLocalRef(localObject);
    onReadyMethod = env->GetStaticMethodID(bridgeClass, "onIl2CppReady", "()V");
    headMethod = env->GetStaticMethodID(bridgeClass, "dispatchHead",
            "(JJJJ)Ldev/frostbite/api/CallbackInfoReturnable;");
    tailMethod = env->GetStaticMethodID(bridgeClass, "dispatchTail", "(JJJJ)V");
    headStaticMethod = env->GetStaticMethodID(bridgeClass, "dispatchHeadStatic",
            "(JJJ)Ldev/frostbite/api/CallbackInfoReturnable;");
    tailStaticMethod = env->GetStaticMethodID(bridgeClass, "dispatchTailStatic", "(JJJ)V");
    cancelledMethod = env->GetMethodID(callbackClass, "isCancelled", "()Z");
    valueMethod = env->GetMethodID(callbackClass, "getReturnValue", "()Ljava/lang/Object;");
    objectAddressMethod = env->GetMethodID(objectClass, "nativeAddress", "()J");
    if (env->ExceptionCheck() || !onReadyMethod || !headMethod || !tailMethod ||
            !headStaticMethod || !tailStaticMethod ||
            !cancelledMethod || !valueMethod || !objectAddressMethod) return JNI_ERR;
#if defined(__aarch64__) || defined(__arm__)
    headUniversalMethod = env->GetStaticMethodID(bridgeClass, "dispatchHeadUniversal",
            "(JJ[JLjava/lang/String;)Ldev/frostbite/api/CallbackInfoReturnable;");
    tailUniversalMethod = env->GetStaticMethodID(bridgeClass, "dispatchTailUniversal", "(JJ[JLjava/lang/String;)V");
    auto global = [&](const char *name) {
        jclass local = env->FindClass(name);
        jclass ref = local ? static_cast<jclass>(env->NewGlobalRef(local)) : nullptr;
        if (local) env->DeleteLocalRef(local);
        return ref;
    };
    objectBaseClass = global("java/lang/Object");
    il2cppObjectCtorClass = static_cast<jclass>(env->NewGlobalRef(objectClass));
    il2cppObjectCtor = env->GetMethodID(objectClass, "<init>", "(J)V");
    boxIntClass = global("java/lang/Integer");
    boxLongClass = global("java/lang/Long");
    boxFloatClass = global("java/lang/Float");
    boxDoubleClass = global("java/lang/Double");
    boxBoolClass = global("java/lang/Boolean");
    boxIntValueOf = env->GetStaticMethodID(boxIntClass, "valueOf", "(I)Ljava/lang/Integer;");
    boxLongValueOf = env->GetStaticMethodID(boxLongClass, "valueOf", "(J)Ljava/lang/Long;");
    boxFloatValueOf = env->GetStaticMethodID(boxFloatClass, "valueOf", "(F)Ljava/lang/Float;");
    boxDoubleValueOf = env->GetStaticMethodID(boxDoubleClass, "valueOf", "(D)Ljava/lang/Double;");
    boxBoolValueOf = env->GetStaticMethodID(boxBoolClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    unboxIntValue = env->GetMethodID(boxIntClass, "intValue", "()I");
    unboxLongValue = env->GetMethodID(boxLongClass, "longValue", "()J");
    unboxFloatValue = env->GetMethodID(boxFloatClass, "floatValue", "()F");
    unboxDoubleValue = env->GetMethodID(boxDoubleClass, "doubleValue", "()D");
    unboxBoolValue = env->GetMethodID(boxBoolClass, "booleanValue", "()Z");
    if (env->ExceptionCheck() || !headUniversalMethod || !tailUniversalMethod || !objectBaseClass ||
            !il2cppObjectCtor || !boxIntValueOf || !boxLongValueOf || !boxFloatValueOf ||
            !boxDoubleValueOf || !boxBoolValueOf || !unboxIntValue || !unboxLongValue ||
            !unboxFloatValue || !unboxDoubleValue || !unboxBoolValue) return JNI_ERR;
#endif
    BNM::Loading::AddOnLoadedEvent(onBnmReady);
    bool hooked = BNM::Loading::TryLoadByJNI(env);
    __android_log_print(ANDROID_LOG_INFO, "Frostbite", "BNM loader hooks: %s", hooked ? "installed" : "unavailable");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeEnumerate(
        JNIEnv *env, jclass, jstring assembly, jstring nameSpace, jstring targetClass) {
    const char *assemblyChars = env->GetStringUTFChars(assembly, nullptr);
    const char *namespaceChars = env->GetStringUTFChars(nameSpace, nullptr);
    const char *classChars = env->GetStringUTFChars(targetClass, nullptr);
    std::string assemblyName = assemblyChars ? assemblyChars : "";
    std::string namespaceName = namespaceChars ? namespaceChars : "";
    std::string className = classChars ? classChars : "";
    if (assemblyChars) env->ReleaseStringUTFChars(assembly, assemblyChars);
    if (namespaceChars) env->ReleaseStringUTFChars(nameSpace, namespaceChars);
    if (classChars) env->ReleaseStringUTFChars(targetClass, classChars);
    BNM::Image image(assemblyName);
    BNM::Class klass(namespaceName, className, image);
    std::vector<BNM::MethodBase> methods = klass ? klass.GetMethods(false) : std::vector<BNM::MethodBase>{};
    jclass recordClass = env->FindClass("dev/frostbite/runtime/ResolvedMethod");
    if (!recordClass) return nullptr;
    jmethodID ctor = env->GetMethodID(recordClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V");
    if (!ctor) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(methods.size()), recordClass, nullptr);
    size_t output = 0;
    for (const auto &method : methods) {
        auto *info = method.GetInfo();
        if (!info || !info->name || !method.GetOffset()) continue;
        std::string signature = info->name;
        signature += (info->flags & 0x10) ? "(static" : "(self";
        for (int p = 0; p < info->parameters_count; ++p) {
            signature += ";";
            signature += canonicalType(info->parameters[p]);
        }
        signature += ")";
        std::string returnType = canonicalType(info->return_type);
        std::string abi(1, abiType(info->return_type));
        abi += ':';
        for (int p = 0; p < info->parameters_count; ++p) abi += abiType(info->parameters[p]);
        jstring jAssembly = env->NewStringUTF(assemblyName.c_str());
        jstring jNamespace = env->NewStringUTF(namespaceName.c_str());
        jstring jClass = env->NewStringUTF(className.c_str());
        jstring jSignature = env->NewStringUTF(signature.c_str());
        jstring jReturn = env->NewStringUTF(returnType.c_str());
        jstring jAbi = env->NewStringUTF(abi.c_str());
        jobject record = env->NewObject(recordClass, ctor, jAssembly, jNamespace, jClass,
                jSignature, jReturn, jAbi, static_cast<jlong>(method.GetOffset()));
        env->SetObjectArrayElement(result, static_cast<jsize>(output++), record);
        env->DeleteLocalRef(record);
        env->DeleteLocalRef(jAssembly);
        env->DeleteLocalRef(jNamespace);
        env->DeleteLocalRef(jClass);
        env->DeleteLocalRef(jSignature);
        env->DeleteLocalRef(jReturn);
        env->DeleteLocalRef(jAbi);
    }
    if (output == methods.size()) return result;
    jobjectArray compact = env->NewObjectArray(static_cast<jsize>(output), recordClass, nullptr);
    for (size_t i = 0; i < output; ++i) {
        jobject item = env->GetObjectArrayElement(result, static_cast<jsize>(i));
        env->SetObjectArrayElement(compact, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return compact;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeInstall(JNIEnv *, jclass, jlong address, jint kind,
                                                       jint argumentCount, jboolean isStatic) {
    if (!address || kind < 0 || kind > 4 || argumentCount < 0 || argumentCount > 2) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(installMutex);
    void *target = reinterpret_cast<void *>(static_cast<uintptr_t>(address));
    for (const auto &slot : slots)
        if (slot.address == target) return slot.kind == kind && slot.argumentCount == argumentCount ? JNI_TRUE : JNI_FALSE;
    int index = -1;
    for (int i = 0; i < kSlots; ++i)
        if (!slots[i].address) { index = i; break; }
    if (index < 0) return JNI_FALSE;
    slots[index].address = target;
    slots[index].kind = kind;
    slots[index].argumentCount = argumentCount;
    void *replacement = isStatic ?
        (argumentCount == 0 ?
            (kind == 0 ? sVoidReplacements0[index] : kind == 1 ? sBoolReplacements0[index] :
             kind == 2 ? sIntReplacements0[index] : kind == 3 ? sFloatReplacements0[index] : sPointerReplacements0[index]) :
         argumentCount == 1 ?
            (kind == 0 ? sVoidReplacements1[index] : kind == 1 ? sBoolReplacements1[index] :
             kind == 2 ? sIntReplacements1[index] : kind == 3 ? sFloatReplacements1[index] : sPointerReplacements1[index]) :
            (kind == 0 ? sVoidReplacements2[index] : kind == 1 ? sBoolReplacements2[index] :
             kind == 2 ? sIntReplacements2[index] : kind == 3 ? sFloatReplacements2[index] : sPointerReplacements2[index]))
      : (argumentCount == 0 ?
            (kind == 0 ? voidReplacements0[index] : kind == 1 ? boolReplacements0[index] :
             kind == 2 ? intReplacements0[index] : kind == 3 ? floatReplacements0[index] : pointerReplacements0[index]) :
         argumentCount == 1 ?
            (kind == 0 ? voidReplacements1[index] : kind == 1 ? boolReplacements1[index] :
             kind == 2 ? intReplacements1[index] : kind == 3 ? floatReplacements1[index] : pointerReplacements1[index]) :
            (kind == 0 ? voidReplacements2[index] : kind == 1 ? boolReplacements2[index] :
             kind == 2 ? intReplacements2[index] : kind == 3 ? floatReplacements2[index] : pointerReplacements2[index]));
    int status = DobbyHook(target, replacement, &slots[index].original);
    if (status != 0 || !slots[index].original) {
        slots[index] = {};
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Universal hook install (AArch64 only): resolves the method, classifies its arguments
// to registers, and routes it through the register-preserving trampoline. Handles any
// signature the fixed-shape path cannot (float/double/long/struct args, >2 args, long/
// double returns). Returns false on ARM32 or when the signature is not yet supported.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeInstallUniversal(
        JNIEnv *env, jclass, jlong address, jstring assembly, jstring nameSpace,
        jstring targetClass, jstring methodName, jint paramCount, jboolean isStatic) {
#if defined(__aarch64__) || defined(__arm__)
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) return JNI_FALSE;
    BNM::MethodBase method = klass.GetMethod(jstr(env, methodName), paramCount);
    auto *info = method.GetInfo();
    if (!info || !info->methodPointer || !info->invoker_method) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(installMutex);
    if (g_universalSlots >= kSlots) return JNI_FALSE;
    int slot = g_universalSlots;
    UniversalSlot record;
    record.address = address;
    if (!classifyUniversal(record, info, isStatic == JNI_FALSE)) return JNI_FALSE;
    record.used = true;
    g_uslots[slot] = record;
    frostbite_slot_stackbytes[slot] = (uint32_t) record.stackBytes;
    int status = DobbyHook(reinterpret_cast<void *>(static_cast<uintptr_t>(address)),
                           reinterpret_cast<void *>(frostbite_slot_entries[slot]),
                           &frostbite_origin_table[slot]);
    if (status != 0 || !frostbite_origin_table[slot]) {
        g_uslots[slot] = UniversalSlot{};
        return JNI_FALSE;
    }
    g_universalSlots++;
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeGetField(
        JNIEnv *env, jclass, jlong self, jstring assembly, jstring nameSpace,
        jstring targetClass, jstring field, jchar abi) {
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) { logError("nativeGetField: class not found"); return 0; }
    BNM::FieldBase base = klass.GetField(jstr(env, field));
    if (!base.IsValid()) { logError("nativeGetField: field not found"); return 0; }
    return fieldGet(reinterpret_cast<void *>(static_cast<uintptr_t>(self)), base, abi);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeSetField(
        JNIEnv *env, jclass, jlong self, jstring assembly, jstring nameSpace,
        jstring targetClass, jstring field, jchar abi, jlong value) {
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) { logError("nativeSetField: class not found"); return; }
    BNM::FieldBase base = klass.GetField(jstr(env, field));
    if (!base.IsValid()) { logError("nativeSetField: field not found"); return; }
    fieldSet(reinterpret_cast<void *>(static_cast<uintptr_t>(self)), base, abi, value);
}

// Create a new IL2CPP instance of a class (il2cpp_object_new) and run its matching constructor.
// Returns the new object's address (0 on failure). For a value type (e.g. Vector3) the result is
// a boxed object whose fields can be read/written through the usual accessors.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeNewInstance(
        JNIEnv *env, jclass, jstring assembly, jstring nameSpace, jstring targetClass,
        jstring argumentAbi, jlongArray arguments) {
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) { logError("nativeNewInstance: class not found"); return 0; }
    auto *object = klass.CreateNewInstance();
    if (!object) { logError("nativeNewInstance: allocation failed"); return 0; }
    std::string argAbi = jstr(env, argumentAbi);
    jsize count = arguments ? env->GetArrayLength(arguments) : 0;
    std::vector<jlong> values(count ? count : 1, 0);
    if (count) env->GetLongArrayRegion(arguments, 0, count, values.data());
    // A value type is allocated boxed, but its constructor's `this` is the UNBOXED value pointer
    // (object + header). Field offsets are header-inclusive, so reads afterward still line up.
    void *instance = object;
    auto *klassPtr = klass.GetClass();
    if (klassPtr && abiType(&klassPtr->byval_arg) == 'S')
        instance = reinterpret_cast<char *>(object) + sizeof(BNM::IL2CPP::Il2CppObject);
    // Run the constructor whose parameter count matches. A zero-arg request skips an absent ctor.
    BNM::MethodBase ctor = klass.GetMethod(".ctor", (int) argAbi.size());
    if (ctor.GetInfo())
        invokeMethod(instance, ctor, 'V', argAbi, values.data());
    else if (!argAbi.empty())
        logError("nativeNewInstance: no constructor with the given argument count");
    return (jlong) reinterpret_cast<uintptr_t>(object);
}

// Create an IL2CPP string from a Java string (for string method arguments).
using StringNewFn = BNM::IL2CPP::Il2CppObject *(*)(const char *);
StringNewFn g_stringNew = nullptr;
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeNewString(JNIEnv *env, jclass, jstring value) {
    if (!g_stringNew)
        g_stringNew = reinterpret_cast<StringNewFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_string_new"));
    if (!g_stringNew) { logError("il2cpp_string_new unavailable"); return 0; }
    std::string text = jstr(env, value);
    return (jlong) reinterpret_cast<uintptr_t>(g_stringNew(text.c_str()));
}

// Resolve a class to its System.Type object (for GetComponent(Type), typeof(...), etc).
using TypeObjectFn = BNM::IL2CPP::Il2CppObject *(*)(const void *);
TypeObjectFn g_typeObject = nullptr;
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeTypeObject(
        JNIEnv *env, jclass, jstring assembly, jstring nameSpace, jstring targetClass) {
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) { logError("nativeTypeObject: class not found"); return 0; }
    auto *klassPtr = klass.GetClass();
    if (!klassPtr) return 0;
    if (!g_typeObject)
        g_typeObject = reinterpret_cast<TypeObjectFn>(DobbySymbolResolver("libil2cpp.so", "il2cpp_type_get_object"));
    if (!g_typeObject) { logError("il2cpp_type_get_object unavailable"); return 0; }
    return (jlong) reinterpret_cast<uintptr_t>(g_typeObject(&klassPtr->byval_arg));
}

// IL2CPP array layout: Il2CppObject header (2 ptrs) + bounds ptr + max_length (ptr-sized) + elements.
// So length lives at 3 pointers in, element data at 4 pointers in. Works on arm64 and arm32.
extern "C" JNIEXPORT jint JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeArrayLength(JNIEnv *, jclass, jlong array) {
    if (!array) return 0;
    auto *base = reinterpret_cast<char *>(static_cast<uintptr_t>(array));
    return (jint) *reinterpret_cast<uintptr_t *>(base + 3 * sizeof(void *));
}

// Read one reference-type element (a pointer) from an object array, or 0 if out of range.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeArrayGetObject(JNIEnv *, jclass, jlong array, jint index) {
    if (!array || index < 0) return 0;
    auto *base = reinterpret_cast<char *>(static_cast<uintptr_t>(array));
    uintptr_t length = *reinterpret_cast<uintptr_t *>(base + 3 * sizeof(void *));
    if ((uintptr_t) index >= length) return 0;
    auto *elements = reinterpret_cast<void **>(base + 4 * sizeof(void *));
    return (jlong) reinterpret_cast<uintptr_t>(elements[index]);
}

// Persist a captured object: returns a weak GC handle (0 on failure). The object is not
// kept alive — the handle just lets the mod re-resolve it later while the game still holds it.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeGcPersist(JNIEnv *, jclass, jlong self) {
    if (!self || !resolveGc()) return 0;
    return (jlong) g_gcWeakNew(reinterpret_cast<void *>(static_cast<uintptr_t>(self)), 0);
}

// Resolve a handle to the current object address, or 0 if it has been collected.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeGcTarget(JNIEnv *, jclass, jlong handle) {
    if (!handle || !resolveGc()) return 0;
    return (jlong) reinterpret_cast<uintptr_t>(g_gcTarget(static_cast<uint32_t>(handle)));
}

// Release a handle. Safe to call once; the handle must not be used afterward.
extern "C" JNIEXPORT void JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeGcFree(JNIEnv *, jclass, jlong handle) {
    if (!handle || !resolveGc()) return;
    g_gcFree(static_cast<uint32_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_frostbite_runtime_NativeBridge_nativeInvoke(
        JNIEnv *env, jclass, jlong self, jstring assembly, jstring nameSpace,
        jstring targetClass, jstring selector, jchar returnAbi, jstring argumentAbi,
        jlongArray arguments) {
    BNM::Image image(jstr(env, assembly));
    BNM::Class klass(jstr(env, nameSpace), jstr(env, targetClass), image);
    if (!klass) { logError("nativeInvoke: class not found"); return 0; }
    std::string select = jstr(env, selector);
    std::string name = select.substr(0, select.find('('));
    std::string argAbi = jstr(env, argumentAbi);
    BNM::MethodBase method = klass.GetMethod(name, (int) argAbi.size());
    if (!method.GetInfo()) { logError("nativeInvoke: method not found"); return 0; }
    jsize count = arguments ? env->GetArrayLength(arguments) : 0;
    std::vector<jlong> values(count ? count : 1, 0);
    if (count) env->GetLongArrayRegion(arguments, 0, count, values.data());
    return invokeMethod(reinterpret_cast<void *>(static_cast<uintptr_t>(self)),
                        method, returnAbi, argAbi, values.data());
}
