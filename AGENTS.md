# AGENTS.md

Fabric mod ("终末之环" / End Ring) for Minecraft 26.2. Single Gradle project, no submodules.

## Build & verify
- `./gradlew build` — compiles both source sets, runs data-gen, and validates mixins. This is the CI check (`.github/workflows/build.yml`); run it before finishing.
- `./gradlew haloTest` — runs the standalone `HaloPhysics` collision-avoidance scenarios (pure Java, no MC/JUnit; source in `src/test`). Wired into `check`.
- `./gradlew runClient` / `./gradlew runServer` — launch dev instances.
- Requires **JDK 25** (`sourceCompatibility`/`release = 25`). Older JDKs will not compile.
- Bump versions in `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`), not in `build.gradle`.

## Mappings (important)
Code uses **Mojang (mojmap) names**, e.g. `ServerPlayer`, `LivingEntity`, `Identifier`, `Attributes.ARMOR` — NOT Yarn. Match existing class/method names when writing new code.

## Structure
Two split source sets (`loom { splitEnvironmentSourceSets() }`):
- `src/main` — common + server logic. Entrypoint `com.hongshaoluobotou.EndRing`.
- `src/client` — client-only rendering. Entrypoint `...client.EndRingClient`. Cannot be referenced from `src/main`.
- Package root is `com.hongshaoluobotou` (does not match modid `endring`).

## Adding features — wiring you must update
- **New mixin**: create the class under `mixin/` (main) or `client/mixin/` (client), then register it in `src/main/resources/endring.mixins.json` or `src/client/resources/endring.client.mixins.json`. Both configs set `requireAnnotations: true` and `compatibilityLevel: JAVA_25`; unregistered mixins do nothing.
- **New network packet**: define a `CustomPacketPayload` record (see `DeathAnimationPayload`), register its type via `PayloadTypeRegistry` in `EndRingEvents.register()`, and add a receiver in `EndRingClient`.
- **Item/registry changes** go through `ModItems`; items use `.setId(KEY)` with a `ResourceKey`.
- **New data component**: register a `DataComponentType` in `ModComponents` (called from `EndRing.onInitialize`). For tooltips, make the component's value type implement `TooltipProvider` and register it via `ItemComponentTooltipProviderRegistry.addLast(...)` — do NOT override the deprecated `Item.appendHoverText`.
- Server-side gameplay events are all registered in `EndRingEvents.register()`; per-tick logic lives in `tickPlayer`.

## Conventions
- Use `EndRing.id("path")` to build namespaced `Identifier`s.
- Ring state (stored totems, regen progress) is persisted in the item's dedicated `ModComponents.END_RING` component (`EndRingComponent`) via `EndRingItem` helpers — do not add separate storage.
- Mixin injected methods are prefixed `endring$`.
- Indentation is tabs.
- Lang keys: item tooltip uses `desc1..descN` (count controlled by `LORE_LINES` in `EndRingItem`) plus a `totems` key; keep `en_us.json` and `zh_cn.json` in sync.
</content>
</invoke>
