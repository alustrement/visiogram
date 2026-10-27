# Visiogram

Client Telegram pour Android TV, pensé pour la télécommande, avec appels audio et vidéo.

## Architecture

| Brique | Rôle |
|---|---|
| [TDLib](https://core.telegram.org/tdlib) via [`tdl-coroutines`](https://github.com/g000sha256/tdl-coroutines) | Compte, contacts, messages, et partie Telegram des appels (création, échange de clés, serveurs relais, signalisation) |
| [NTgCalls](https://github.com/pytgcalls/ntgcalls) | Média WebRTC des appels privés et des chats vidéo de groupe : micro, caméra, haut-parleur, vidéo reçue (I420) |
| [webrtc-audio-processing](https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing) (AEC3), dans `libvisio_apm.so` | Annulation d'écho, filtre passe-haut et réduction de bruit sur le micro, absentes du WebRTC de NTgCalls |
| Compose for TV (`androidx.tv:tv-material`) | Interface pilotée au D-pad |

Le pont entre les deux est [`CallManager`](app/src/main/java/app/visiogram/calls/CallManager.kt) :

1. `createCall` / `acceptCall` côté TDLib, avec le protocole annoncé par NTgCalls (`NTgCalls.getProtocol()`).
2. À `callStateReady`, NTgCalls reçoit la clé déjà négociée par TDLib (`skipExchange`), puis `connectP2p` avec les serveurs et les versions de librairie.
3. La signalisation circule dans les deux sens : `onSignalingData` → `sendCallSignalingData`, `updateNewCallSignalingData` → `sendSignalingData`.
4. Le son et la vidéo reçus arrivent par `onFrames` (périphériques « externes » en lecture) : le son en PCM 48 kHz mono, la vidéo en I420, affichée dans un `TextureViewRenderer` WebRTC.

Les chats vidéo de groupe passent par [`GroupCallManager`](app/src/main/java/app/visiogram/calls/GroupCallManager.kt) :

1. `createVideoChat` si aucun chat vidéo n'est en cours dans le groupe, puis `getGroupCall` (sinon TDLib n'envoie pas les mises à jour des participants).
2. NTgCalls `createCall(chatId)` produit le payload de connexion ; TDLib `joinVideoChat` renvoie la réponse du serveur, transmise à NTgCalls `connect`.
3. L'audio des participants arrive par `onFrames`, un bloc par participant qui parle, mixé par `SpeakerSink`. Chaque caméra ou partage d'écran distant est abonné avec `addIncomingVideo` (endpoint + groupes SSRC issus de `updateGroupCallParticipant`), et les images arrivent par `onFrames` avec le SSRC correspondant.
4. La caméra est captée par l'application ([`CameraSource`](app/src/main/java/app/visiogram/calls/CameraSource.kt), Camera2) pour régler luminosité et cadrage, puis envoyée en source « externe » NTgCalls (`sendExternalFrame`, I420). Les aperçus locaux viennent directement de cette capture.
5. Le micro est capté de la même façon ([`MicSource`](app/src/main/java/app/visiogram/calls/MicSource.kt), source « voix », 48 kHz mono) pour appliquer le gain, puis envoyé en source externe par blocs de 10 ms.
6. Le son reçu est joué par l'application ([`SpeakerSink`](app/src/main/java/app/visiogram/calls/SpeakerSink.kt)) plutôt que par NTgCalls, dont la file de lecture n'a pas de limite et qui annonce une latence de sortie nulle : la file est plafonnée à 60 ms, la latence réelle de sortie est mesurée (horodatages de l'`AudioTrack`), et la vidéo reçue est retardée d'autant ([`VideoDelay`](app/src/main/java/app/visiogram/calls/VideoDelay.kt)), plus le réglage « Synchro image » pour ce qui ne se mesure pas (casque Bluetooth en face).
7. L'[`EchoCanceller`](app/src/main/java/app/visiogram/calls/EchoCanceller.kt) (AEC3) reçoit tout ce que `SpeakerSink` joue comme référence et nettoie le micro avant le gain ; il cherche l'écho jusqu'à 960 ms de délai (sortie HDMI, réencodage pour une barre de son).
8. Le mode audio reste `MODE_NORMAL` : sur la Shield, `MODE_IN_COMMUNICATION` route tout le son vers une sortie muette au lieu du HDMI.
9. Une seule communication à la fois : un appel privé reçu pendant un chat vidéo est refusé (occupé).

## Mise en route

1. Créer une application sur <https://my.telegram.org/apps>.
2. Renseigner `local.properties`, qui n'est pas versionné :
   ```properties
   telegram.apiId=123456
   telegram.apiHash=0123456789abcdef0123456789abcdef
   ```
3. Compiler et installer :
   ```bash
   ./gradlew installDebug          # ou : adb -s shield:5555 install -r -g app/build/outputs/apk/debug/app-debug.apk
   adb logcat -s CallManager Telegram
   ```

Prérequis : JDK 17+, Android SDK Platform 37 (exigé par `tdl-coroutines` et Compose 1.12).

La bibliothèque native d'annulation d'écho est versionnée dans `app/src/main/jniLibs` : la compilation normale n'a besoin ni du NDK ni de Meson. Après une modification de [`echo_canceller.cpp`](app/src/main/cpp/echo_canceller.cpp), la reconstruire avec [`tools/build-echo-canceller.sh`](tools/build-echo-canceller.sh) (NDK, Meson, Ninja, accès réseau pour les sources).

## État

- [x] Connexion par QR code, ou par numéro, code et mot de passe 2FA
- [x] Grille de contacts, choix entre appel vidéo et appel audio
- [x] Appels sortants et entrants (sonnerie, décrocher, refuser), micro, caméra, raccrocher, emojis de vérification
- [x] Chats vidéo de groupe : liste des groupes (ceux avec un chat vidéo en cours en premier), démarrer ou rejoindre en vidéo ou en audio, grille des participants (OK sur une vignette pour la passer en plein écran), micro, caméra, quitter, terminer pour tous (admins)
- [ ] Chats vidéo de groupe validés de bout en bout
- [x] Réglages image et micro accessibles pendant un appel (bouton « Réglages » → panneau latéral, appliqués en direct), via le composant commun [`MediaControls`](app/src/main/java/app/visiogram/ui/components/MediaControls.kt)
- [x] Onglet Réglages : aperçu caméra (résolution, images/s et luminosité mesurées), choix de la caméra et de la qualité envoyée (480p/720p/1080p), luminosité et contraste (courbe logicielle, la webcam ignorant la compensation d'exposition), cadrage (zoom ×1 à ×3 appliqué par la caméra), volume du micro (−12 à +24 dB avec limiteur doux), vu-mètre micro avec « enregistrer 5 s puis réécouter », son de test sur la sortie audio des appels, test de l'annulation d'écho (réduction en dB et délai trouvé)
- [ ] Indicateur « en train de parler » (NTgCalls n'expose pas les niveaux audio en groupe)
- [ ] Grands chats vidéo en mode diffusion (segments MTProto) et diffusions RTMP
- [ ] Appels de groupe chiffrés (conférences, ajout d'une 3ᵉ personne à un appel privé)
- [ ] Validé de bout en bout avec un client officiel
- [ ] Service au premier plan et notification plein écran pour les appels reçus quand l'app est en arrière-plan
- [ ] Messagerie (liste des discussions, lecture, réponses rapides)
- [ ] Historique des appels
