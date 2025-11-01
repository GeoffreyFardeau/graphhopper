# 🎯 Exemples d'utilisation des tags `access` et `motor_vehicle`

## 📖 Guide pratique avec cas réels

Ce document présente des exemples concrets d'utilisation des nouveaux tags OSM dans GraphHopper.

---

## 🚗 Scénarios de routing avec `motor_vehicle`

### Scénario 1 : Zone piétonne avec livraisons autorisées

**OSM :**
```xml
<way id="12345">
  <tag k="highway" v="pedestrian"/>
  <tag k="motor_vehicle" v="delivery"/>
</way>
```

**GraphHopper - Routing de livraison :**
```java
EnumEncodedValue<MotorVehicle> mvEnc = 
    encodingManager.getEnumEncodedValue(MotorVehicle.KEY, MotorVehicle.class);

// Pour un véhicule de livraison
MotorVehicle access = edge.get(mvEnc);
if (access == MotorVehicle.DELIVERY) {
    // Autoriser si c'est un véhicule de livraison
    if (vehicle.isDeliveryVehicle()) {
        return normalWeight;
    } else {
        return Double.POSITIVE_INFINITY; // Bloquer les autres
    }
}
```

### Scénario 2 : Route résidentielle "Sauf riverains"

**OSM :**
```xml
<way id="23456">
  <tag k="highway" v="residential"/>
  <tag k="motor_vehicle" v="destination"/>
</way>
```

**GraphHopper - Routing classique :**
```java
MotorVehicle access = edge.get(mvEnc);
if (access == MotorVehicle.DESTINATION) {
    // Pénaliser ou bloquer selon le type de trajet
    if (!isDestinationRoute(edge, targetNode)) {
        weight *= 10.0; // Forte pénalité pour dissuader
        // Ou : return Double.POSITIVE_INFINITY; // Bloquer complètement
    }
}
```

### Scénario 3 : Parking clients

**OSM :**
```xml
<way id="34567">
  <tag k="amenity" v="parking"/>
  <tag k="motor_vehicle" v="customers"/>
  <tag k="name" v="Parking Carrefour"/>
</way>
```

**GraphHopper - Recherche de parking :**
```java
// Filtrer les parkings selon l'accès
if (edge.get(mvEnc) == MotorVehicle.CUSTOMERS) {
    // N'inclure que si destination à proximité
    if (isNearDestination(edge, userDestination)) {
        includeParkingSpot(edge);
    }
}
```

---

## 🚶 Scénarios avec `access` (tous modes de transport)

### Scénario 4 : Propriété privée tolérante

**OSM :**
```xml
<way id="45678">
  <tag k="highway" v="track"/>
  <tag k="access" v="permissive"/>
</way>
```

**GraphHopper - Information utilisateur :**
```java
Access access = edge.get(accessEnc);
if (access == Access.PERMISSIVE) {
    // Ajouter une note dans les instructions
    instruction.setNote("Passage sur propriété privée (accès toléré)");
    // Appliquer une légère pénalité (peut être révoqué)
    weight *= 1.2;
}
```

### Scénario 5 : Zone militaire

**OSM :**
```xml
<way id="56789">
  <tag k="highway" v="service"/>
  <tag k="access" v="military"/>
</way>
```

**GraphHopper - Bloquer complètement :**
```java
Access access = edge.get(accessEnc);
if (access == Access.MILITARY) {
    // Bloquer pour tous les utilisateurs civils
    return Double.POSITIVE_INFINITY;
}
```

### Scénario 6 : Chemin forestier

**OSM :**
```xml
<way id="67890">
  <tag k="highway" v="track"/>
  <tag k="access" v="forestry"/>
  <tag k="foot" v="yes"/> <!-- Piétons autorisés -->
</way>
```

**GraphHopper - Routing différencié :**
```java
Access access = edge.get(accessEnc);
if (access == Access.FORESTRY) {
    // Bloquer les véhicules motorisés (sauf forestiers)
    if (vehicle.isMotorized() && !vehicle.isForestryVehicle()) {
        return Double.POSITIVE_INFINITY;
    }
    // Les piétons peuvent passer (vérifier foot=yes séparément)
}
```

---

## 🛣️ Combinaisons complexes

### Scénario 7 : Route avec hiérarchie de restrictions

**OSM :**
```xml
<way id="78901">
  <tag k="highway" v="residential"/>
  <tag k="access" v="no"/>              <!-- Tout interdit par défaut -->
  <tag k="motor_vehicle" v="destination"/> <!-- Sauf motorisés en destination -->
  <tag k="bicycle" v="yes"/>            <!-- Et vélos toujours OK -->
  <tag k="foot" v="yes"/>               <!-- Et piétons toujours OK -->
</way>
```

**GraphHopper - Gestion de la hiérarchie :**
```java
// Ordre de priorité : spécifique > général
EnumEncodedValue<Access> accessEnc = lookup.getEnumEncodedValue(Access.KEY, Access.class);
EnumEncodedValue<MotorVehicle> mvEnc = lookup.getEnumEncodedValue(MotorVehicle.KEY, MotorVehicle.class);

Access generalAccess = edge.get(accessEnc);
MotorVehicle motorAccess = edge.get(mvEnc);

if (vehicle.isMotorized()) {
    // Vérifier le tag spécifique motor_vehicle en premier
    if (motorAccess != MotorVehicle.MISSING) {
        return evaluateAccess(motorAccess);
    }
    // Sinon, utiliser le tag général access
    return evaluateAccess(generalAccess);
} else if (vehicle.isBicycle()) {
    // Les vélos ont leur propre tag bicycle=yes
    return allowBicycle ? normalWeight : Double.POSITIVE_INFINITY;
}
```

### Scénario 8 : Parking de supermarché

**OSM :**
```xml
<way id="89012">
  <tag k="amenity" v="parking"/>
  <tag k="access" v="customers"/>
  <tag k="name" v="Parking Leclerc"/>
  <tag k="capacity" v="200"/>
  <tag k="fee" v="no"/>
</way>
```

**GraphHopper - Recherche intelligente de parking :**
```java
Access access = edge.get(accessEnc);

if (access == Access.CUSTOMERS) {
    // Déterminer si l'utilisateur est un "client"
    if (isNearPOI(edge, Arrays.asList("shop", "supermarket"))) {
        // C'est un parking client accessible
        return new ParkingSpot(edge, "customers_only", 0.0 /* no fee */);
    } else {
        // Pas pour le public général
        return null;
    }
}
```

---

## 🎨 Custom Model avec restrictions d'accès

### Exemple : Éviter les routes privées

**Custom Model JSON :**
```json
{
  "priority": [
    {
      "if": "access == Access.PRIVATE",
      "multiply_by": 0.1
    },
    {
      "if": "access == Access.PERMISSIVE", 
      "multiply_by": 0.8
    },
    {
      "if": "motor_vehicle == MotorVehicle.DESTINATION",
      "multiply_by": 0.5
    }
  ],
  "speed": [
    {
      "if": "access == Access.CUSTOMERS",
      "limit_to": 5
    }
  ]
}
```

**GraphHopper - Custom Weighting :**
```java
CustomModel customModel = new CustomModel();

// Pénaliser fortement les accès privés
customModel.addToPriority(If.create(
    "access == Access.PRIVATE", 
    MULTIPLY, 
    0.1
));

// Pénaliser modérément les accès permissifs
customModel.addToPriority(If.create(
    "access == Access.PERMISSIVE", 
    MULTIPLY, 
    0.8
));

// Limiter la vitesse sur les zones clients
customModel.addToSpeed(If.create(
    "access == Access.CUSTOMERS", 
    LIMIT, 
    5
));

Weighting weighting = new CustomWeighting(
    encoder, 
    encodingManager, 
    customModel
);
```

---

## 🚚 Cas d'usage professionnel

### Transport routier (HGV)

```java
public class HGVRouter {
    
    public double calculateEdgeWeight(EdgeIteratorState edge) {
        MotorVehicle mvAccess = edge.get(motorVehicleEnc);
        Access generalAccess = edge.get(accessEnc);
        
        // Bloquer complètement certains accès
        if (generalAccess == Access.NO || 
            generalAccess == Access.PRIVATE ||
            mvAccess == MotorVehicle.NO) {
            return Double.POSITIVE_INFINITY;
        }
        
        // Forte pénalité pour destination (probable qu'on ne puisse pas passer)
        if (mvAccess == MotorVehicle.DESTINATION) {
            return baseWeight * 50;
        }
        
        // Pénalité modérée pour customers
        if (mvAccess == MotorVehicle.CUSTOMERS) {
            return baseWeight * 10;
        }
        
        // Poids normal pour les autres cas
        return baseWeight;
    }
}
```

### Livraison du dernier kilomètre

```java
public class DeliveryRouter {
    
    public boolean canAccess(EdgeIteratorState edge) {
        MotorVehicle mvAccess = edge.get(motorVehicleEnc);
        Access generalAccess = edge.get(accessEnc);
        
        // Les véhicules de livraison peuvent accéder aux zones delivery
        if (mvAccess == MotorVehicle.DELIVERY || 
            generalAccess == Access.DELIVERY) {
            return true;
        }
        
        // Aussi aux zones destination
        if (mvAccess == MotorVehicle.DESTINATION || 
            generalAccess == Access.DESTINATION) {
            return true;
        }
        
        // Et aux accès normaux
        if (mvAccess == MotorVehicle.YES || 
            generalAccess == Access.YES) {
            return true;
        }
        
        // Tout le reste est bloqué
        return false;
    }
}
```

---

## 📊 Statistiques et analytics

### Analyser les restrictions dans une zone

```java
public Map<Access, Integer> analyzeAccessRestrictions(BBox bbox) {
    Map<Access, Integer> stats = new HashMap<>();
    
    // Parcourir toutes les edges dans la bbox
    graph.getEdgeIteratorState().forEachRemaining(edge -> {
        if (bbox.contains(edge.getBaseNode())) {
            Access access = edge.get(accessEnc);
            stats.merge(access, 1, Integer::sum);
        }
    });
    
    return stats;
}

// Utilisation
Map<Access, Integer> stats = analyzeAccessRestrictions(cityBBox);
System.out.println("Private roads: " + stats.get(Access.PRIVATE));
System.out.println("Destination only: " + stats.get(Access.DESTINATION));
System.out.println("Public roads: " + stats.get(Access.YES));
```

---

## 🔍 Debugging et validation

### Vérifier les restrictions d'une route

```java
public void printAccessInfo(long wayId) {
    EdgeIteratorState edge = findEdge(wayId);
    
    Access access = edge.get(accessEnc);
    MotorVehicle mvAccess = edge.get(motorVehicleEnc);
    
    System.out.println("Way ID: " + wayId);
    System.out.println("General access: " + access);
    System.out.println("Motor vehicle: " + mvAccess);
    
    // Interpréter
    if (access == Access.PRIVATE) {
        System.out.println("⚠️  Private access - routing blocked");
    }
    if (mvAccess == MotorVehicle.DESTINATION) {
        System.out.println("⚠️  Destination only - apply penalty");
    }
    if (mvAccess == MotorVehicle.DELIVERY) {
        System.out.println("🚚 Delivery vehicles only");
    }
}
```

---

## 💡 Best Practices

### 1. Toujours vérifier la hiérarchie

```java
// ❌ Mauvais - ignore motor_vehicle
if (edge.get(accessEnc) == Access.NO) {
    return Double.POSITIVE_INFINITY;
}

// ✅ Bon - vérifie les deux niveaux
Access general = edge.get(accessEnc);
MotorVehicle specific = edge.get(motorVehicleEnc);

// Le tag spécifique a priorité
if (specific != MotorVehicle.MISSING) {
    return evaluateMotorVehicleAccess(specific);
}
return evaluateGeneralAccess(general);
```

### 2. Gérer MISSING correctement

```java
// ❌ Mauvais - MISSING bloque tout
if (edge.get(mvEnc) == MotorVehicle.MISSING) {
    return Double.POSITIVE_INFINITY;
}

// ✅ Bon - MISSING signifie "pas de tag, utiliser défaut"
MotorVehicle mv = edge.get(mvEnc);
if (mv == MotorVehicle.MISSING) {
    // Pas de restriction spécifique, utiliser comportement par défaut
    return getDefaultWeight(edge);
}
```

### 3. Logger pour le debug

```java
if (LOGGER.isDebugEnabled()) {
    Access access = edge.get(accessEnc);
    if (access == Access.PRIVATE || access == Access.NO) {
        LOGGER.debug("Blocked edge {} due to access={}", 
                     edge.getEdge(), access);
    }
}
```

---

## 🎓 Pour aller plus loin

### Ressources
- Documentation OSM : https://wiki.openstreetmap.org/wiki/Key:access
- Exemples de panneaux : Voir `OSM_ACCESS_TAGS_README.md`
- Tests unitaires : `OSMAccessParserTest.java`, `OSMMotorVehicleParserTest.java`

### Contribuer
Si vous trouvez des cas d'usage non couverts ou des bugs, n'hésitez pas à :
1. Ajouter des tests
2. Documenter le cas d'usage
3. Proposer des améliorations

---

**Dernière mise à jour :** 2025-10-29  
**Version :** 1.0  
**Status :** ✅ Production ready
