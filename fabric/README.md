# Arnis In-Game (Fabric)

This Fabric 1.20.1 mod maps the Overworld to OpenStreetMap, with a geographic
origin at central London. It targets Java 17:

- London (`51.5074, -0.1278`) maps to Minecraft X/Z `(0, 0)`.
- East is positive X; north is negative Z.
- New and existing worlds spawn on the surface at X/Z `(0, 0)`.
- As Overworld chunks load, the mod requests a 512-block geographic tile and
  builds mapped roads, waterways, water areas, and building footprints on the
  chunk's existing terrain.
- Tiles are fetched asynchronously from the Arnis Overpass service, cached in
  `<world>/arnis/osm-tiles/`, and processed no faster than one network request
  per second. Processed-chunk markers prevent the mod overwriting player edits
  after a restart.
- `/arnis coordinates` reports the geographic location, `/arnis status` reports
  tile progress, `/arnis retry` retries failed tiles around the player, and
  `/arnis attribution` displays the required OpenStreetMap credit.
- `LondonProjection.chunkCenter` and `LondonProjection.project` expose the
  world/geographic coordinate conversion used by chunk requests.

The map is expandable as players explore; it is not pre-generated. Minecraft's
vanilla terrain and biomes remain in place: this mod currently overlays OSM
features and does not yet import Arnis elevation, land cover, or detailed
building interiors. The Web Mercator projection covers latitudes up to
approximately +/-85.05 degrees; polar regions are outside its supported map.
Tiles are a local snapshot and are not automatically refreshed.
Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).

## Build and install

Install JDK 17 and Gradle 8.7, then from this directory run:

```text
gradle build
```

Copy `build/libs/arnis-fabric-0.1.1.jar` into the `mods` folder of a Minecraft
1.20.1 Fabric installation. Fabric API is bundled inside this mod, so Prism
does not need a separate Fabric API mod. Create a new world or open an existing
one; the mod sets its spawn to the London origin. Internet access is needed the
first time each tile is visited.

The world map uses the same spherical Web Mercator scale adjustment as Arnis's
existing `--one-world` projection, anchored to central London.
