# The Portrait — version 0.2.0

Launcher Android expérimental pour le Xiaomi Redmi Note 14 5G / HyperOS.

Cette version apporte les fonctions demandées pour la prochaine étape de test :

- portrait de violoniste peint, avec teinte et fissures lorsque l'état décrépit est activé ; un mouvement discret de l'archet apparaît pendant la lecture musicale ;
- panneau musical relié aux sessions multimédias Android (titre, artiste, lecture/pause, piste précédente/suivante) ;
- carnet à quatre pages : Tâches journalières, Liste de courses, Notes et À venir ; les entrées sont ajoutables, modifiables, supprimables et conservées après fermeture ;
- montre à gousset qui s'ouvre sur un cadran et affiche le nombre de pas du jour lu dans Santé Connect / Health Connect ;
- barre de recherche manuscrite qui ouvre une recherche Google ;
- ChatGPT ajouté à la grille, et icône The Portrait ajoutée au tiroir d'applications Android ;
- suppression du texte « Glissez pour révéler / restaurer ».

## Permissions à activer après installation

### Pas — Santé Connect / Health Connect

The Portrait demande uniquement la lecture des pas du jour (`READ_STEPS`). Aucune donnée de santé n'est écrite par l'application et rien n'est envoyé à un serveur.

1. Ouvrir Google Fit → Profil → Paramètres.
2. Dans « Santé Connect », activer « Synchroniser Fit avec Santé Connect » et autoriser le partage des pas.
3. Ouvrir The Portrait et toucher la montre à gousset.
4. Accorder à The Portrait l'autorisation de lire les pas dans Santé Connect.

Sur Android 14 et ultérieur, Health Connect peut aussi fournir les pas comptés par l'appareil si cette fonction est disponible sur le téléphone. Les données agrégées par Health Connect sont utilisées pour éviter de compter plusieurs fois des enregistrements qui se chevauchent.

### Musique

1. Toucher le panneau musical dans The Portrait.
2. Android ouvre la page d'accès aux notifications.
3. Autoriser « The Portrait – musique ».

Cette autorisation système est nécessaire pour voir les sessions multimédias actives et envoyer les commandes de lecture. Elle est révocable à tout moment dans les paramètres Android.

## Gestes et commandes

- Glisser horizontalement hors des widgets : passer de l'état préservé à l'état décrépit et inversement.
- Dans le carnet : glisser horizontalement pour changer de page ; glisser verticalement pour faire défiler les entrées.
- Toucher une case dans Tâches ou Courses : cocher/décocher.
- Toucher « Ajouter… » : créer une entrée.
- Appui long sur une entrée : modifier ou supprimer.
- Dans Notes et À venir : toucher une entrée pour la modifier.
- Toucher la montre : ouvrir/fermer le cadran des pas.
- Toucher la barre Google : saisir une recherche.
- Toucher une icône : ouvrir l'application correspondante si elle est installée.

## Compiler l'APK via GitHub Actions

Le workflow `.github/workflows/build-apk.yml` est conservé. Après avoir remplacé les fichiers du dépôt par ceux de cette version et validé les changements sur `main`, l'action **Build The Portrait APK** doit se lancer. Quand elle affiche une coche verte :

1. Ouvrir l'exécution dans l'onglet Actions.
2. Descendre à « Artifacts ».
3. Télécharger `ThePortrait-debug-apk`.
4. Décompresser l'archive et installer `app-debug.apk`.

**Important pour la mise à jour du prototype 0.1 :** les APK de débogage générés par des exécutions GitHub différentes peuvent être signés par des clés différentes. Désinstaller l'ancien « The Portrait » avant d'installer le nouvel APK évite un conflit de signature. Cela efface les données locales du prototype précédent, qui ne stockait pas encore de données persistantes.

## Limites de cette version

- L'animation musicale est un mouvement de l'archet superposé à l'image du violoniste ; ce n'est pas encore une animation complète du bras ou du corps.
- L'état décrépit est appliqué par désaturation, assombrissement et fissures sur le même portrait ; il ne s'agit pas encore d'une seconde peinture dédiée.
- La lecture des pas dépend de Santé Connect et des sources autorisées à y écrire des pas. Google Fit doit être synchronisé avec Santé Connect pour y contribuer.
- L'accès musical nécessite d'activer manuellement l'accès spécial aux notifications.
- L'affichage météo reste un texte provisoire, non connecté à une source météo.
- Le rendu est un prototype artistique et fonctionnel, pas encore le thème final avec un jeu complet d'icônes peintes individuellement.

Le projet a été préparé pour la compilation GitHub Actions, mais l'environnement local de cette session ne contient pas le SDK Android : l'APK 0.2.0 n'a donc pas été compilé ni testé ici. Le workflow du dépôt effectuera la compilation après l'envoi des modifications.
