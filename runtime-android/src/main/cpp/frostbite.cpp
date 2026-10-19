#include <jni.h>
#include <android/log.h>
#include <dobby.h>
#include <BNM/Loading.hpp>
#include <BNM/Image.hpp>
#include <BNM/Class.hpp>
#include <BNM/MethodBase.hpp>
#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#include <utility>

namespace {
constexpr int kSlots = 32;
JavaVM *vm = nullptr;
jclass bridgeClass = nullptr;
jclass callbackClass = nullptr;
jmethodID onReadyMethod = nullptr;
jmethodID headMethod = nullptr;
jmethodID tailMethod = nullptr;
jmethodID cancelledMethod = nullptr;
jmethodID valueMethod = nullptr;
jmethodID objectAddressMethod = nullptr;
jclass objectClass = nullptr;
std::mutex installMutex;

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

char abiType(const BNM::IL2CPP::Il2CppType *type) {
    if (!type) return '?';
    using BNM::IL2CPP::Il2CppTypeEnum;
    switch (type->type) {
        case Il2CppTypeEnum::IL2CPP_TYPE_VOID: return 'V';
        case Il2CppTypeEnum::IL2CPP_TYPE_BOOLEAN: return 'Z';
        case Il2CppTypeEnum::IL2CPP_TYPE_I4: return 'I';
        case Il2CppTypeEnum::IL2CPP_TYPE_R4: return 'F';
        case Il2CppTypeEnum::IL2CPP_TYPE_CLASS:
        case Il2CppTypeEnum::IL2CPP_TYPE_OBJECT:
        case Il2CppTypeEnum::IL2CPP_TYPE_STRING:
        case Il2CppTypeEnum::IL2CPP_TYPE_ARRAY:
        case Il2CppTypeEnum::IL2CPP_TYPE_SZARRAY: return 'P';
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

void onBnmReady() {
    AttachedEnv attached;
    JNIEnv *env = attached.get();
    if (!env || !onReadyMethod) return;
    env->CallStaticVoidMethod(bridgeClass, onReadyMethod);
    clearJavaException(env);
}
}

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
    cancelledMethod = env->GetMethodID(callbackClass, "isCancelled", "()Z");
    valueMethod = env->GetMethodID(callbackClass, "getReturnValue", "()Ljava/lang/Object;");
    objectAddressMethod = env->GetMethodID(objectClass, "nativeAddress", "()J");
    if (env->ExceptionCheck() || !onReadyMethod || !headMethod || !tailMethod ||
            !cancelledMethod || !valueMethod || !objectAddressMethod) return JNI_ERR;
    BNM::Loading::AddOnLoadedEvent(onBnmReady);
    BNM::Loading::AllowLateInitHook();
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
                                                       jint argumentCount) {
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
    void *replacement = argumentCount == 0 ?
            (kind == 0 ? voidReplacements0[index] : kind == 1 ? boolReplacements0[index] :
             kind == 2 ? intReplacements0[index] : kind == 3 ? floatReplacements0[index] : pointerReplacements0[index]) :
            argumentCount == 1 ?
            (kind == 0 ? voidReplacements1[index] : kind == 1 ? boolReplacements1[index] :
             kind == 2 ? intReplacements1[index] : kind == 3 ? floatReplacements1[index] : pointerReplacements1[index]) :
            (kind == 0 ? voidReplacements2[index] : kind == 1 ? boolReplacements2[index] :
             kind == 2 ? intReplacements2[index] : kind == 3 ? floatReplacements2[index] : pointerReplacements2[index]);
    int status = DobbyHook(target, replacement, &slots[index].original);
    if (status != 0 || !slots[index].original) {
        slots[index] = {};
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
