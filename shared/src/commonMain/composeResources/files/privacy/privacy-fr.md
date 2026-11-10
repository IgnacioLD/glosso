# Confidentialité et analytique - Glosso

Résumé des données que le produit traite et de la manière dont cela est déclaré
dans Google Play. Ne constitue pas un avis juridique.

## Audio

- L'audio est envoyé au serveur **uniquement** pour reconnaître et évaluer la
  prononciation.
- Il est traité en mémoire et **supprimé à la fin de la requête**. Il n'est ni
  conservé, ni journalisé, ni réutilisé.
- En mode hors ligne (phase ultérieure), l'audio ne quitte pas l'appareil.

## Analytique first-party (`/v1/events`)

Nous recueillons des événements produit dans notre propre base de données
(Postgres, `glosso.analytics_event`). **Aucun partage avec des tiers.**

| Donnée | Exemple | Pourquoi |
|---|---|---|
| Identifiant d'installation aléatoire | UUID aléatoire créé au premier lancement | Compter les appareils uniques |
| Identifiant de session | UUID aléatoire | Regrouper les événements d'une session |
| Version de l'app / plateforme | `1.0.0`, `android` | Compatibilité |
| Langue pratiquée | `en_GB` | Amélioration du cours |
| Nom et propriétés de l'événement | `score_attempt`, `score`, `level` | Analyser l'usage et la difficulté |

**Nous ne recueillons pas** : Advertising ID, IMEI, MAC, `ANDROID_ID` comme
identifiant stable, modèle ou marque de l'appareil, localisation précise,
contacts, SMS, texte libre de l'utilisateur, messages d'erreur, ni l'audio.

Le serveur **ne stocke pas l'IP** dans la table d'événements. Les événements
serveur (`score_attempt`, `503`, erreurs) sont enregistrés de la même manière,
sans audio.

## Compte : continuer avec Google (facultatif)

Il permet d'emporter le compte sur un nouveau téléphone. Il est facultatif ; l'app fonctionne de la même façon sans lui.

- **Continuer avec Google** : l'utilisateur choisit un compte Google du
  téléphone (Android Credential Manager). Seule l'identité est demandée : le
  serveur vérifie l'ID token et conserve **uniquement** l'identifiant opaque que
  Google attribue au compte (`sub`). Il ne conserve **ni** l'e-mail, ni le nom,
  ni la photo, ni le token, et ne demande aucun accès à Gmail, Drive ou à un
  autre service. L'e-mail n'est affiché que sur le téléphone.

## Conservation

- Configurable via `GLOSSO_ANALYTICS_RETENTION_DAYS` (par défaut **180 jours**).
- Purge au démarrage du serveur.

## Sécurité

- **TLS obligatoire** : l'app ne parle au point de terminaison qu'en HTTPS.
- `/metrics`, `/stats`, `/admin/events` et `/v1/events/summary` sont protégés par
  un jeton (`GLOSSO_ADMIN_TOKEN`) ; sans jeton, ils restent fermés.
- Limite de taille des événements et de l'audio ; en-têtes de sécurité (HSTS,
  `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`).

## Déclaration Google Play (Data safety)

- **Données collectées / Infos personnelles / ID utilisateur** : oui,
  uniquement si l'utilisateur continue avec Google (facultatif) : l'identifiant
  du compte Google.
- **Données collectées / Activité dans l'app** : "Other actions" -> oui
  (événements d'usage).
- **Données collectées / Appareil ou autres ID** : "Device or other IDs" -> oui
  (identifiant d'installation aléatoire).
- **Données collectées / Infos et performances de l'app** : "Crash logs" et
  "Diagnostics" -> oui, à partir de l'app 2.5.4 (type d'erreur, endroit du
  code et pile d'appels technique sans messages ni données utilisateur ;
  vitesse de notation, version d'Android, téléphone ou tablette). Non partagé,
  facultatif, pour l'analytique et le fonctionnement de l'app.
- **Partage avec des tiers** : non.
- **Chiffré en transit** : oui.
- **Suppression des données** : prévoir un canal de support et, le cas échéant,
  un bouton in-app pour réinitialiser l'id et supprimer les données associées.

## Votre choix

- Les statistiques d'utilisation sont activées par défaut et l'accueil le
  signale. Elles se désactivent à tout moment dans Réglages > Statistiques
  d'utilisation ; l'app fonctionne exactement pareil sans elles et le serveur
  cesse alors aussi d'enregistrer l'événement de note de cet appareil.
- « Supprimer mes données d'utilisation », au même endroit, efface tous les
  événements de cet appareil sur notre serveur.
- Les événements envoyés par l'app : ouverture, durée de session, accueil
  commencé et terminé, leçon commencée, terminée ou quittée, exercice terminé
  (type, tranche de note de 10 points, maîtrisé ou non), essai de prononciation
  noté sur le téléphone (tranche de note, maîtrisé ou non, identifiants des
  règles d'erreur) et micro refusé.

À partir de l'app 2.5.4, l'app envoie aussi :

- À l'ouverture de l'app, le contexte : version d'Android, téléphone ou
  tablette, mode sombre activé ou non, si le modèle optionnel sur l'appareil
  est installé, langue de l'interface et accent choisi. Jamais le modèle de
  l'appareil, la marque ni un identifiant matériel.
- La vitesse de notation (millisecondes) et si l'essai a été noté sur le
  téléphone.
- Écrans vus (parmi une liste fixe), étapes de leçon atteintes, audio écouté
  (quel type d'audio, normal ou lent, et la réécoute de votre propre
  enregistrement, jamais l'enregistrement), résultats de « À toi » et de la
  révision (retrouvée, partielle, pas cette phrase, révélée), histoires
  ouvertes et part lue (pourcentage), et la durée de la série quotidienne.
- Réglages modifiés (lequel et sa nouvelle valeur : langue apprise, accent,
  langue de l'interface, transcription phonétique, traductions, rappel
  quotidien, téléchargements en Wi-Fi uniquement).
- Téléchargements de leçons ou du modèle (commencé, terminé ou échoué, taille
  en Mo, code d'erreur court) et modèle supprimé.
- Erreurs visibles par type (notation échouée, hors ligne, non évalué, micro,
  contenu, audio) et rapports de plantage : le type d'erreur, l'endroit du code
  de l'app et la pile d'appels technique (classe, méthode, fichier et ligne),
  envoyés au lancement suivant pour les plantages fatals ; jamais le message
  d'erreur, le contenu ni rien de ce que vous saisissez.
- Autorisation des notifications accordée ou non et résultat de la connexion
  avec Google (réussie, annulée, aucun compte, échouée).

Le tout avec les mêmes règles : uniquement des valeurs oui/non, des nombres
bornés et des identifiants, jamais de texte libre ni d'audio ; conservé 180
jours, et « Supprimer mes données d'utilisation » efface tout, rapports de
plantage compris. Le serveur rejette tout autre événement.
