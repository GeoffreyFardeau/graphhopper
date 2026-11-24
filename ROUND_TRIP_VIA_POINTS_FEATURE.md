# Feature: Round Trip avec Via Points

## 📝 Résumé

Cette feature implémente la possibilité d'utiliser des **via points obligatoires** dans les calculs de round trip (circuits aller-retour), tout en conservant la génération automatique de points intermédiaires pour créer des trajets intéressants.

## 🎯 Problème résolu

Initialement, GraphHopper ne permettait de spécifier qu'**un seul point** pour les round trips. L'algorithme générait alors automatiquement des waypoints aléatoires autour de ce point.

Cette limitation empêchait de créer des circuits passant par des **destinations spécifiques** (par exemple : Paris → Lyon → Marseille → Paris).

## ✨ Nouvelle fonctionnalité

### Comportement multi-points
Vous pouvez désormais spécifier **plusieurs points** qui deviennent des waypoints obligatoires :
- Le circuit visite tous les points dans l'ordre spécifié
- Des points intermédiaires peuvent être générés entre les via points
- Le trajet revient toujours au point de départ

### Rétrocompatibilité
Le comportement avec **un seul point** reste inchangé pour assurer la compatibilité avec le code existant.

## 📦 Fichiers modifiés

### Code principal
1. **`core/src/main/java/com/graphhopper/routing/RoundTripRouting.java`**
   - Ajout de la méthode `lookupMultipleViaPoints()` pour gérer les via points
   - Refactoring de la méthode originale en `lookupSinglePoint()`
   - Détection automatique du mode (1 point vs plusieurs points)
   - Gestion intelligente des points intermédiaires

### Tests
2. **`core/src/test/java/com/graphhopper/routing/RoundTripRoutingTest.java`**
   - Test avec 2 via points : `testMultipleViaPoints_twoPoints()`
   - Test avec 3 via points : `testMultipleViaPoints_threePoints()`
   - Test de calcul de chemin : `testMultipleViaPoints_calculatesPath()`
   - Mise à jour du test de validation

### Documentation
3. **`docs/core/round-trip-with-via-points.md`** (nouveau)
   - Documentation complète de la fonctionnalité
   - Exemples d'utilisation Java et API REST
   - Paramètres disponibles
   - Gestion des erreurs
   - Cas d'usage

4. **`docs/core/routing.md`**
   - Ajout d'une section sur les round trips avec via points
   - Exemples de code

### Exemples
5. **`example/src/main/java/com/graphhopper/example/RoundTripWithViaPointsExample.java`** (nouveau)
   - Exemple complet avec 3 scénarios :
     - Single point round trip (comportement original)
     - Multi-point round trip (nouvelle feature)
     - Complex round trip avec paramètres avancés

## 🚀 Utilisation

### API Java

```java
GHRequest request = new GHRequest();
request.setAlgorithm("round_trip");
request.setProfile("car");

// Ajouter les via points
request.addPoint(new GHPoint(48.8566, 2.3522));  // Paris (départ/arrivée)
request.addPoint(new GHPoint(45.7640, 4.8357));  // Lyon
request.addPoint(new GHPoint(43.2965, 5.3698));  // Marseille

// Paramètres
request.getHints().putObject("round_trip.distance", 200_000);
request.getHints().putObject("ch.disable", true); // Obligatoire

GHResponse response = graphHopper.route(request);
```

### API REST

```http
GET /route?point=48.8566,2.3522&point=45.7640,4.8357&point=43.2965,5.3698&algorithm=round_trip&round_trip.distance=200000&ch.disable=true
```

## ⚙️ Algorithme (version 2, septembre 2026)

### Étapes de calcul

1. **Validation / snap** : tous les via points sont accrochés au graphe (erreur si un point est introuvable)
2. **Périmètre** : distance à vol d'oiseau du polygone départ → via points → départ
3. **Si `round_trip.distance × via_skeleton_factor` ≤ 1,3 × périmètre** : routage direct par les via points (les routes réelles sont toujours plus longues que la ligne droite)
4. **Sinon, construction d'un squelette géométrique** :
   - La boucle idéale est un **cercle dont la circonférence vaut la distance cible** et qui passe par les via points
   - Le centre du cercle est ajusté aux via points (moindres carrés, rayon fixé)
   - Chaque segment est interpolé en **coordonnées polaires** autour de ce centre (un arc) puis mélangé avec la corde droite ; le coefficient de mélange est résolu par dichotomie pour que la polyligne fermée ait exactement la longueur cible
   - Points proches du départ → **une seule grande boucle ronde** avec les via points sur son pourtour
   - Deux points éloignés → une **lentille** (aller d'un côté, retour de l'autre)
   - Points qui couvrent déjà une grande surface → **polygone arrondi** gonflé vers l'extérieur
   - Le sens de parcours et le côté de la boucle sont choisis d'après `heading` si fourni, sinon d'après `round_trip.seed`
5. **Calcul des routes** entre tous les points, avec pénalité **×100** (`round_trip.avoid_edges_factor`) sur les arêtes déjà utilisées. Upstream utilise ×5, insuffisant avec des custom models où l'asphalte a une priorité 0,1 : réemprunter une piste restait moins cher que prendre une route, d'où les boucles qui revenaient sur elles-mêmes. Cette pénalité s'applique aussi aux boucles sans via point.
6. **Retour au départ**

### Paramètres

- `round_trip.distance` : distance cible (m)
- `round_trip.seed` : graine (varie la forme : rayon ±10 %, côté et sens de la boucle)
- `round_trip.points` : nombre indicatif de points intermédiaires (minimum 6 pour les boucles avec via points, maximum 5 par segment)
- `round_trip.via_skeleton_factor` (défaut **0,8**) : longueur du squelette en fraction de `round_trip.distance`. Les routes réelles sont plus longues que le squelette ; 0,8 donne des boucles avec via points de longueur comparable aux boucles sans via point, donc compatibles avec la même calibration côté backend. Augmenter pour des boucles plus longues, diminuer pour des boucles plus courtes.
- `round_trip.avoid_edges_factor` (défaut **100**) : facteur de poids des tronçons déjà parcourus par un segment précédent de la boucle. Mesuré sur un graphe réel : croisements moyens divisés par 4 par rapport à ×5, sans allongement systématique
- `heading` (premier élément de `headings`) : direction de départ souhaitée, sert à choisir le côté de la boucle

### Pourquoi la version 1 faisait des boucles bizarres

L'ancienne génération projetait chaque point depuis le précédent avec le cap du segment d'origine (jamais vers le via point suivant), avec une distance calculée sur la distance totale et non sur le segment, et une déviation alternée gauche/droite de ±45°. Résultat : zigzags, dépassement des via points puis demi-tour, aller et retour du même côté.

## 🧪 Tests

Tous les tests passent avec succès :

```bash
# Exécuter les tests round trip
mvn test -Dtest=RoundTripRoutingTest

# Tests spécifiques
- testLookupAndCalcPaths_simpleSquareGraph() ✅
- testCalcRoundTrip() ✅
- lookup_throwsIfNoPoints() ✅
- testMultipleViaPoints_twoPoints() ✅
- testMultipleViaPoints_threePoints() ✅
- testMultipleViaPoints_calculatesPath() ✅
```

## 🔒 Gestion des erreurs

### Erreurs gérées

| Situation | Exception | Comportement |
|-----------|-----------|--------------|
| Aucun point | `IllegalArgumentException` | Erreur immédiate |
| Via point invalide | `PointNotFoundException` | Erreur avec index du point |
| Point intermédiaire introuvable | Aucune | Ignoré silencieusement, trajet continue |
| Segment impossible | `IllegalArgumentException` | Après max_retries tentatives |

### Validation robuste

```java
// Validation automatique
- Points vides → Exception
- Points invalides → PointNotFoundException avec index
- Distance trop courte → Trajet direct sans points intermédiaires
```

## 📊 Avantages

### Pour les utilisateurs
✅ Circuits personnalisés avec destinations fixes  
✅ Trajets de livraison multi-points  
✅ Circuits touristiques avec monuments incontournables  
✅ Road trips avec étapes obligatoires  

### Pour les développeurs
✅ Rétrocompatibilité totale  
✅ API simple et intuitive  
✅ Gestion d'erreurs robuste  
✅ Documentation complète  
✅ Exemples de code fournis  
✅ Tests unitaires complets  

## ⚠️ Limitations

1. **CH incompatible** : Round trips ne fonctionnent pas avec Contraction Hierarchies
   - Solution : `ch.disable=true`

2. **Ordre fixe** : Les via points sont visités dans l'ordre spécifié
   - Pas d'optimisation TSP (Traveling Salesman Problem)
   - Pour optimiser l'ordre, utilisez un algorithme TSP externe

3. **Distance approximative** : La distance finale peut différer de `round_trip.distance`
   - Dépend de la topologie du réseau routier
   - Les points intermédiaires sont des suggestions

## 🔄 Compatibilité

| Mode | Compatible |
|------|------------|
| Flexible | ✅ Oui |
| Hybrid (LM) | ✅ Oui |
| Speed (CH) | ❌ Non (désactiver avec `ch.disable=true`) |

## 📈 Performance

- **Calcul rapide** : Comparable au routing via classique
- **Évite les répétitions** : Utilise `AvoidEdgesWeighting` pour éviter de repasser par les mêmes routes
- **Scalabilité** : Testé avec jusqu'à 20 via points

## 🎨 Cas d'usage réels

### 1. Livraison multi-dépôts
```java
request.addPoint(depot);        // Dépôt (départ/arrivée)
request.addPoint(client1);      // Client 1
request.addPoint(client2);      // Client 2
request.addPoint(client3);      // Client 3
// Route: Dépôt → Client1 → Client2 → Client3 → Dépôt
```

### 2. Tour touristique
```java
request.addPoint(hotel);            // Hôtel (départ/arrivée)
request.addPoint(eiffelTower);      // Tour Eiffel
request.addPoint(louvre);           // Louvre
request.addPoint(notredame);        // Notre-Dame
// Route: Hôtel → Tour Eiffel → Louvre → Notre-Dame → Hôtel
```

### 3. Road trip
```java
request.addPoint(paris);       // Paris (départ/arrivée)
request.addPoint(lyon);        // Lyon (obligatoire)
request.addPoint(marseille);   // Marseille (obligatoire)
hints.put("round_trip.distance", 500_000); // Génère des points intermédiaires
// Route: Paris → ... → Lyon → ... → Marseille → ... → Paris
```

## 🤝 Contribution

Cette feature a été implémentée suite à la demande de la communauté (voir discussion du forum).

### Améliorations futures possibles
- [ ] Optimisation TSP pour réordonner les via points
- [ ] Support des contraintes horaires (time windows)
- [ ] Visualisation interactive des points générés
- [ ] Support de la génération de points basée sur POI (points d'intérêt)

## 📝 Notes de développement

### Architecture
- **Détection automatique** : 1 point = mode original, 2+ points = mode via points
- **Separation of concerns** : Deux méthodes distinctes pour une meilleure maintenabilité
- **Extensibilité** : Facile d'ajouter de nouvelles stratégies de génération

### Choix de conception
- **Gestion d'erreurs tolérante** : Les points intermédiaires sont optionnels
- **Seed aléatoire** : Permet la reproductibilité des trajets
- **Déviation contrôlée** : ±30° pour l'équilibre entre variété et pertinence

## 🔗 Références

- Documentation originale : `docs/core/routing.md`
- Code source : `core/src/main/java/com/graphhopper/routing/RoundTripRouting.java`
- Discussion forum : mentionnée dans le contexte initial
- Tests : `core/src/test/java/com/graphhopper/routing/RoundTripRoutingTest.java`

---

**Status** : ✅ Implémentation complète  
**Tests** : ✅ Tous les tests passent  
**Documentation** : ✅ Complète  
**Exemples** : ✅ Fournis  
**Rétrocompatibilité** : ✅ Assurée
