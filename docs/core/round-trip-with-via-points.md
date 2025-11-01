# Round Trip avec Via Points

## Vue d'ensemble

Cette fonctionnalité étend l'algorithme de round trip de GraphHopper pour permettre la spécification de via points obligatoires tout en générant automatiquement des points intermédiaires pour créer des itinéraires plus intéressants.

## Fonctionnalité

### Comportement original (1 point)
Lorsqu'un seul point est fourni, l'algorithme fonctionne comme avant :
- Génère automatiquement des via points autour du point de départ
- Crée un circuit qui revient au point de départ
- Utilise les paramètres `distance` et `points` pour contrôler la longueur et la complexité du trajet

**Exemple d'utilisation :**
```java
List<GHPoint> points = Arrays.asList(new GHPoint(48.8566, 2.3522)); // Paris
// Crée un round trip autour de Paris
```

### Nouveau comportement (2+ points)
Lorsque plusieurs points sont fournis :
- Les points spécifiés deviennent des waypoints obligatoires
- Le circuit passe par tous les points dans l'ordre : A → B → C → ... → A
- Des points intermédiaires peuvent être générés entre les via points pour des trajets plus intéressants
- Le trajet se termine toujours au point de départ

**Exemple d'utilisation :**
```java
List<GHPoint> viaPoints = Arrays.asList(
    new GHPoint(48.8566, 2.3522),  // Paris (départ/arrivée)
    new GHPoint(45.7640, 4.8357),  // Lyon
    new GHPoint(43.2965, 5.3698)   // Marseille
);
// Crée un round trip : Paris → Lyon → Marseille → Paris
```

## API REST

### Requête avec un seul point (comportement original)
```http
GET /route?point=48.8566,2.3522&algorithm=round_trip&round_trip.distance=100000
```

### Requête avec plusieurs via points (nouvelle fonctionnalité)
```http
GET /route?point=48.8566,2.3522&point=45.7640,4.8357&point=43.2965,5.3698&algorithm=round_trip&round_trip.distance=200000
```

### Paramètres disponibles

| Paramètre | Description | Défaut |
|-----------|-------------|--------|
| `algorithm` | Doit être `round_trip` | - |
| `point` | Coordonnées des via points (peut être répété) | - |
| `round_trip.distance` | Distance totale souhaitée en mètres | 10 000 |
| `round_trip.points` | Nombre maximum de points intermédiaires à générer | Calculé automatiquement |
| `round_trip.seed` | Seed pour la génération aléatoire | 0 |

## Comportement détaillé

### Calcul des points intermédiaires

1. **Calcul de la distance totale** : L'algorithme calcule d'abord la distance directe entre tous les via points
2. **Points supplémentaires** : Si `round_trip.distance` > distance des via points, des points intermédiaires sont générés
3. **Distribution** : Les points intermédiaires sont répartis équitablement entre les segments
4. **Déviation** : Les points générés peuvent dévier légèrement du chemin direct (±30°) pour des trajets plus intéressants

### Conditions pour générer des points intermédiaires

- Le segment doit faire plus de 10 km
- La distance totale souhaitée doit être supérieure à la distance directe entre les via points
- Le nombre de points est limité par le paramètre `round_trip.points`

## Gestion des erreurs

### Erreurs courantes

**Aucun point fourni**
```
IllegalArgumentException: At least one point is required for round trip calculation
```

**Via point invalide**
```
PointNotFoundException: Cannot find via point X: [coordinates]
```

**Point intermédiaire introuvable**
```
Les points intermédiaires qui ne peuvent pas être trouvés sont ignorés silencieusement.
Le trajet peut toujours être calculé avec uniquement les via points obligatoires.
```

## Exemples de code

### Exemple Java simple
```java
// Configuration
PMap hints = new PMap();
hints.putObject(Parameters.Algorithms.RoundTrip.DISTANCE, 150_000); // 150 km
hints.putObject(Parameters.Algorithms.RoundTrip.POINTS, 5); // Max 5 points intermédiaires

// Via points
List<GHPoint> viaPoints = Arrays.asList(
    new GHPoint(48.8566, 2.3522),  // Paris
    new GHPoint(45.7640, 4.8357),  // Lyon
    new GHPoint(43.2965, 5.3698)   // Marseille
);

// Lookup
RoundTripRouting.Params params = new RoundTripRouting.Params(hints, 0, 3);
List<Snap> snaps = RoundTripRouting.lookup(viaPoints, edgeFilter, locationIndex, params);

// Calcul des chemins
QueryGraph queryGraph = QueryGraph.create(graph, snaps);
RoundTripRouting.Result result = RoundTripRouting.calcPaths(snaps, pathCalculator);
```

### Exemple avec GHRequest
```java
GHRequest request = new GHRequest();
request.setAlgorithm("round_trip");
request.setProfile("car");

// Ajouter les via points
request.addPoint(new GHPoint(48.8566, 2.3522));  // Paris
request.addPoint(new GHPoint(45.7640, 4.8357));  // Lyon
request.addPoint(new GHPoint(43.2965, 5.3698));  // Marseille

// Paramètres du round trip
request.getHints().putObject("round_trip.distance", 200_000);
request.getHints().putObject("round_trip.points", 3);

GHResponse response = graphHopper.route(request);
```

## Cas d'usage

### Road trip avec destinations fixes
Créer un circuit qui passe par des destinations spécifiques tout en explorant des routes intéressantes entre elles.

### Livraison multi-points
Optimiser un trajet de livraison qui doit passer par plusieurs points et revenir au dépôt.

### Tourisme
Créer des circuits touristiques passant par des monuments ou sites incontournables.

## Limitations

1. **Mode CH désactivé** : Le round trip ne fonctionne pas avec Contraction Hierarchies (CH)
2. **Ordre fixe** : Les via points sont visités dans l'ordre spécifié (pas d'optimisation TSP)
3. **Distance approximative** : La distance finale peut différer de `round_trip.distance` en fonction de la disposition des routes

## Compatibilité

- ✅ Compatible avec le mode Flexible
- ✅ Compatible avec Landmarks (LM)
- ❌ Incompatible avec Contraction Hierarchies (CH)
- ✅ Rétrocompatible : le comportement avec un seul point reste inchangé

## Tests

Des tests unitaires sont disponibles dans `RoundTripRoutingTest.java` :
- `testMultipleViaPoints_twoPoints()` : Test avec 2 via points
- `testMultipleViaPoints_threePoints()` : Test avec 3 via points
- `testMultipleViaPoints_calculatesPath()` : Vérification du calcul de chemin

## Notes de développement

### Architecture
- La méthode `lookup()` détecte automatiquement le nombre de points
- Un seul point → `lookupSinglePoint()` (comportement original)
- Plusieurs points → `lookupMultipleViaPoints()` (nouvelle fonctionnalité)

### Algorithme de génération
1. Validation et snap de tous les via points
2. Calcul de la distance totale entre via points
3. Détermination du nombre de points intermédiaires à générer
4. Génération des points avec déviation aléatoire contrôlée
5. Gestion des erreurs silencieuse pour les points intermédiaires

### Paramètres de déviation
- Angle de déviation : ±30° par rapport à la direction directe
- Distance minimale de segment : 10 km pour générer des points intermédiaires
- Distance par point intermédiaire : ~50 km de distance restante
