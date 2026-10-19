# Outcast / Frostbite desktop design system

## 1. Product

Frostbite Desktop is a Java 27 Swing tool for mod authors. Its two views install the Frostbite runtime into a game APK and add or remove `.frostbite` mods. The interface is a compact desktop workspace with one primary action per view.

## 2. Direction

The concept is a dark workshop: a restrained black canvas, a pale geometric Outcast breach mark, a fixed navigation rail, and a centered assembly surface that stays readable at any window width. Copy is direct and task based. Empty selections state what is missing; running builds show progress; errors state the failure.

References: [Linear's density and sidebar redesign](https://linear.app/changelog/2024-03-20-new-linear-ui), [Raycast's compact desktop treatment](https://www.raycast.com/blog/a-fresh-look-and-feel), and [IBM Plex's technical typography rationale](https://www.ibm.com/design/language/typography/typeface/). These are composition references, not borrowed assets.

## 3. Color

| Token | Hex | Role |
|---|---|---|
| Canvas | `#0E0F10` | Main background and primary button text |
| Rail | `#131516` | Navigation background |
| Surface | `#1C1E20` | Forms and status panel |
| Input | `#25282A` | Fields and secondary actions |
| Separator | `#373A3C` | Thin structural rules |
| Text | `#F3F3EF` | Primary copy and logo |
| Muted | `#AEB2B0` | Supporting copy |
| Highlight | `#D5CBBC` | Primary action, progress, focus |
| Error | `#EF958E` | Failure text |

Computed WCAG contrast: text/canvas 17.25:1, muted/canvas 8.95:1, text/surface 15.03:1, muted/surface 7.80:1, highlight/canvas 11.97:1.

## 4. Typography

Segoe UI is the Windows native sans face. Headings are 32 px bold, rail title 26 px bold, body and field values 14 px, field labels 13 px bold, helper and status text 12 px. Navigation is 14 px bold. Avoid all caps except the Outcast wordmark in the title bar.

## 5. Layout

The default window is 1020 × 680; minimum is 780 × 560. The custom frame can be resized from all edges and corners. The rail is 220 px, reducing to 192 px below 960 px window width. The main content has 36 px horizontal padding and a centered maximum width of 980 px. The secondary status panel hides below 950 px content width. A vertical scroll area keeps actions reachable at short heights.

## 6. Components

The fixed rail holds the two workflow views. Each view has a heading, one supporting sentence, a two part progress rule, file fields with explicit Choose actions, and one build action. The current view has a filled navigation state. Forms preserve their values when changing views. File chooser dialogs use the OS control.

## 7. Surfaces

Surfaces use one elevation level: `#1C1E20` over the canvas with a 19 px corner radius and a thin `#373A3C` rule. Inputs use 12 px corners. Avoid nested decorative cards.

## 8. Icons

The Outcast mark is the primary icon. The title bar uses drawn Windows style minimize, maximize, and close symbols. No mixed icon library is used. Icon only controls have tooltips and accessible button names.

## 9. Assets

The new transparent vector logo is [outcast-breach.svg](logo/outcast-breach.svg). The desktop resource [outcast-logo.svg](src/main/resources/outcast-logo.svg) is the copy rendered at runtime. The branding source files are in this branch's [`logo/`](logo/) directory.

## 10. Motion

Navigation selection and hover tween over 170–240 ms, buttons over 110–180 ms, focus over 190 ms, and view transitions over 340 ms. A progress arc runs only while packaging. Motion communicates state change and does not delay file operations.

## 11. Accessibility

All actions use Swing buttons and fields, with keyboard tab order following the visual order. Focus on file fields has a highlight rule. Supporting copy meets AA contrast. Status uses words as well as color. File chooser and confirmation dialogs are native Swing controls.

## 12. Project constraints

Keep the black palette. Do not add bright green accents, small decorative headers, gradients, glass, or oversized ornamental panels. Keep the Outcast mark monochrome and transparent.

## 13. Future changes

Use these tokens and component treatments in any added view. Preserve the two stage workflow and the responsive minimum width. Prefer clearer copy and spacing over additional decoration.

## 14. Update policy

Update this file whenever the palette, mark, spacing, typography, component style, or motion changes.
