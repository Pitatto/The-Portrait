# The Portrait — prototype Android

Prototype de launcher Android pour le Xiaomi Redmi Note 14 5G (Android 16 / HyperOS 3).

**Ce dossier est le projet source, pas encore l'APK.** Le workflow GitHub inclus peut compiler automatiquement l'APK sans installer Android Studio sur ton PC.

## Obtenir l'APK sans installer d'outils sur ton PC

1. Crée un compte GitHub si tu n'en as pas : https://github.com/signup
2. Depuis un navigateur, ouvre https://github.com/new et crée un dépôt nommé `ThePortrait`. Laisse-le en **Public** pour simplifier l'accès aux actions, puis valide avec **Create repository**.
3. Sur la page du dépôt, choisis **Add file → Upload files**.
4. Décompresse l'archive `ThePortrait_Android_Source.zip` sur ton ordinateur. Envoie le contenu du dossier `ThePortrait_Android` dans le dépôt (y compris le dossier caché `.github`). Valide avec **Commit changes**.
5. Ouvre l'onglet **Actions** du dépôt. Le workflow **Build The Portrait APK** démarre automatiquement. Attends qu'il affiche une coche verte.
6. Ouvre l'exécution terminée, descends à **Artifacts**, puis télécharge `ThePortrait-debug-apk`.
7. Décompresse ce fichier sur ton téléphone et ouvre `app-debug.apk`. Si Android le demande, autorise temporairement l'installation depuis cette source.
8. Pour tester l'écran d'accueil, appuie sur le bouton Accueil et choisis **The Portrait**. Tu pourras revenir à HyperOS en choisissant à nouveau le lanceur système dans les paramètres d'applications par défaut.

Si la compilation échoue, ouvre l'exécution dans **Actions**, copie le premier message d'erreur rouge et renvoie-le-moi : je corrigerai le projet.

## Gestes du prototype

- Glisser vers la gauche : état décrépit.
- Glisser vers la droite : état préservé.
- Toucher la montre : ouvrir/fermer le cadran.
- Toucher une tâche : cocher/décocher.
- Toucher une icône : lancer l'application si elle est installée.

## Applications configurées

Discord, Reddit, Instagram, Facebook, YouTube, YouTube Music, Bandcamp, Outlook, Vinted et Chrome. Les raccourcis utilisent les identifiants Android habituels ; ils peuvent varier selon l'installation.

## Limites connues de cette version

- Prototype graphique dessiné en formes simples : ce n'est pas encore la version finale avec les portraits et icônes peints.
- La transition change au relâchement du glissement ; elle ne suit pas encore le doigt en continu.
- Les tâches ne sont pas conservées après fermeture de l'application.
- La montre est intégrée à l'écran du launcher, pas un widget Android indépendant.
- La compilation distante n'a pas encore été exécutée ; le workflow la réalisera lors de l'envoi sur GitHub.
