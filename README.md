# OpenKatana

Lanceur Android **non officiel** pour jouer à **Daikatana** (Ion Storm, 2000) sur les
consoles portables et smartphones Android ARM64, grâce au moteur du patch communautaire
**Daikatana 1.3** dans son port PortMaster.

> [!IMPORTANT]
> **Vous devez posséder le jeu.** OpenKatana ne contient **aucun fichier du jeu** : ni
> niveaux, ni textures, ni sons, ni musiques. Pour jouer, il vous faut une copie légale de
> Daikatana pour PC (par exemple [GOG](https://www.gog.com/game/daikatana) ou
> [Steam](https://store.steampowered.com/app/242980/Daikatana/)) et copier son dossier
> `data` sur l'appareil. Sans ces fichiers, l'application refuse de lancer le jeu.

## Fonctionnalités

- Vérifie au démarrage la présence des fichiers du jeu (`pak1.pak`, `pak2.pak`, `pak3.pak`).
  S'ils manquent, demande où ils se trouvent (mémoire interne ou carte SD) et retient le
  chemin.
- Écran large : plein écran 16:9 (ou au format de l'écran), FOV adapté, zone de l'encoche
  utilisée.
- Manette intégrée ou Bluetooth (sticks, gâchettes, croix), clavier physique.
- Son (OpenAL).
- Aucune émulation : le moteur ARM64 tourne en natif.

Testé sur **AYN Odin 2 Portal** (Snapdragon 8 Gen 2, Android 13).

## Installation

1. Téléchargez `OpenKatana-x.y.z.apk` depuis la page
   [Releases](../../releases) et installez-le (autorisez l'installation d'applications
   inconnues).
2. Copiez le dossier `data` de votre installation PC de Daikatana sur l'appareil, par exemple
   dans `ROMS/PC/Daikatana/data` sur la carte SD. Une installation patchée en 1.3 sous Windows
   convient aussi : les fichiers supplémentaires (`pak4.pak` à `pak6.pak`, voix…) sont
   utilisés.
3. Lancez **OpenKatana** :
   - autorisez l'**accès à tous les fichiers** : le moteur lit les fichiers du jeu à leur
     emplacement, sans les copier ;
   - choisissez le dossier `data`, ou le dossier qui le contient.
4. Au premier lancement, le runtime Linux est installé (environ 30 secondes, une seule fois),
   puis le jeu démarre. Aux lancements suivants, le jeu démarre directement.

Pour changer de dossier plus tard : **appui long sur l'icône → « Changer le dossier du jeu »**.

### Commandes (manette)

Les commandes sont celles du port PortMaster (`daikatana/cfgs/gamecontroller.cfg`) et se
modifient dans le menu du jeu.

| Bouton | Action |
|---|---|
| Stick gauche / droit | Se déplacer / viser |
| R2 | Tirer |
| L2 | Zoom |
| A | S'accroupir |
| B | Utiliser l'objet sélectionné |
| X | Utiliser (portes, boutons) |
| Y | Changer d'objet du HUD |
| L1 / R1 | Sauter / courir |
| Croix gauche / droite | Arme précédente / suivante |
| Croix haut / bas | Objet précédent / suivant |
| Start | Menu |
| Retour (Android) | Échap |

## Limites actuelles

- **Rendu logiciel** (Mesa llvmpipe), en 1280x720 agrandi au format de l'écran. C'est
  jouable sur un Snapdragon 8 Gen 2, mais pas encore fluide en toutes circonstances.
  L'accélération GPU Adreno (Mesa Turnip + Zink) est prévue.
- **Menus en anglais uniquement** : le moteur ARM64 du port ne charge pas encore les modules
  de langue du patch 1.3 (travail en cours, voir `android/runtime/langconv.py`).
- Uniquement les appareils **ARM64** (arm64-v8a), Android 8.0 ou plus récent.

## Fonctionnement

Le moteur du port est un exécutable **Linux** ARM64 (glibc, SDL2, OpenAL), qu'Android ne
peut pas lancer directement. OpenKatana fournit donc un petit environnement Linux, comme le
fait PortMaster sur les consoles Linux :

```
Appli Android (Java)
 ├─ proot ─► Debian arm64 minimal ─► Xvfb (écran X virtuel) ─► moteur Daikatana
 │                                     └─ libdkbridge.so (LD_PRELOAD) : manette SDL virtuelle
 ├─ affiche l'image de Xvfb (mmap) avec OpenGL ES, mise à l'échelle de l'écran
 ├─ envoie manette/clavier à libdkbridge (socket locale)
 └─ joue le son : OpenAL → ALSA → FIFO → AudioTrack
```

## Structure du dépôt

| Chemin | Contenu |
|---|---|
| `daikatana/` | Port PortMaster d'origine (moteur 1.3 ARM64, `pak4.pak` du patch, configs) |
| `Daikatana.sh` | Script de lancement PortMaster d'origine |
| `android/app/` | Application Android |
| `android/runtime/` | Construction du runtime Linux : rootfs, pont manette, son, proot |
| `.github/workflows/release.yml` | Construction et publication de l'APK |

## Compilation

### Avec GitHub Actions

Poussez un tag pour produire une release avec l'APK :

```sh
git tag v0.3.0
git push origin v0.3.0
```

Le workflow peut aussi être lancé à la main (onglet *Actions*). L'APK est alors disponible en
artefact pendant 1 jour.

Il n'utilise que des runners `ubuntu-latest`, inclus dans le quota gratuit de GitHub, y
compris pour un dépôt privé. Le runtime ARM64 y est construit sous émulation, ce qui prend
environ 20 à 30 minutes par release.

**Signature** : pour que les mises à jour s'installent par-dessus la version précédente,
l'APK doit toujours être signé avec la même clé. Créez-la une fois :

```sh
keytool -genkeypair -v -keystore release.jks -alias openkatana -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # valeur du secret OPENKATANA_KEYSTORE_BASE64
```

Puis ajoutez ces secrets au dépôt (*Settings → Secrets and variables → Actions*) :
`OPENKATANA_KEYSTORE_BASE64`, `OPENKATANA_KEYSTORE_PASSWORD`, `OPENKATANA_KEY_ALIAS`
(`openkatana`) et `OPENKATANA_KEY_PASSWORD`. Sans ces secrets, l'APK est signé avec une clé
de debug. Gardez `release.jks` en lieu sûr et ne le commitez pas.

### En local

Prérequis : JDK 17+, SDK Android (plateforme 36), Python 3, Docker (avec émulation ARM64 sur
un PC x86_64).

```sh
# 1. Runtime Linux arm64 -> android/runtime/out/rootfs.tgz
docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie sh /src/android/runtime/build-runtime.sh

# 2. proot (paquets Termux) -> android/app/src/main/jniLibs/arm64-v8a/
python3 android/runtime/prepare-proot.py

# 3. APK
cd android && ./gradlew assembleRelease
```

`android/runtime/devtools/` contient des outils pour tester le runtime directement sur un
appareil via adb, hors de l'application.

## Crédits et licences

- **Daikatana** © Ion Storm / Eidos Interactive (Square Enix). Marques et contenus du jeu
  appartiennent à leurs propriétaires. Ce projet n'est **ni affilié ni approuvé** par eux.
- **Daikatana 1.3** : patch communautaire de Frank Sapone (maraakate) et de son équipe,
  https://github.com/maraakate/daikatana. Le moteur est redistribué tel que fourni par le
  port PortMaster.
- **Port PortMaster** : JohnnyonFlame, https://portmaster.games.
- **proot** (GPL-2.0) et **libtalloc** (LGPL-3.0) : paquets [Termux](https://termux.dev).
- **Runtime Linux** : paquets [Debian](https://www.debian.org) 13 (SDL2, OpenAL Soft,
  Mesa, Xorg Xvfb, glibc…), chacun sous sa propre licence.
- Icône et illustration d'OpenKatana : créations originales du projet.
