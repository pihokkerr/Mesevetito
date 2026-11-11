# Mesevetítő

Esti diavetítő szkennelt mesekönyvekhez Android-projektorra (Magcubic HY310x, Android 11), távirányítóval.

## Pendrive
- Minden könyv egy mappa, benne az oldalak képei (jpg/png/webp). A mappa neve a cím, az első kép a borító. Az oldalak a fájlnév szerint, természetes sorrendben jönnek (Oldal_2 a 10 előtt).
- A zenék egy külön mappában (pl. `Zene`), mp3/m4a/ogg/flac/wav.
- **Gyűjtemények:** ha egy mappában könyvmappák vannak (pl. `Bogyó és Babóca/`, `Karácsony/`), az a könyvtárban gyűjteményként jelenik meg, a borítója a benne lévő könyvek mozaikja. OK-val belépsz, Vissza gombbal kilépsz. Ha minden könyv egyetlen gyűjtőmappában van (pl. `Mesék/`), az nem lesz külön szint.

## Távirányító
| Gomb | Könyvtárban | Könyvben |
|---|---|---|
| Nyilak | könyv választása | ↓ / ↑ diafilmes lapozás, ← / → gyors lapozás képsorral |
| OK | könyv megnyitása | zene be/ki |
| Vissza | kilépés | vissza a könyvtárba |
| Menü / Play | zene be/ki | zene be/ki |

Az app megjegyzi, hol tartottatok (vékony csík a borító alján). A Képméret gomb kicsinyíti a vetített oldalt, ha a kép széle életlen.

## Build
GitHub Actions minden push után; a legfrissebb APK: Releases → `latest` → `Mesevetito.apk`.
