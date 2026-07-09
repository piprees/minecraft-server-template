# Custom Dimensions (Fork)

Fixed fork of the Custom Dimensions Fabric mod for Minecraft 1.21.1.

## Bug fixes (v1.0.1-fork)

1. **Removed NetherPortalBlockMixin** -- targeted methods that don't exist on `NetherPortalBlock` in 1.21.1 (they live on `AbstractBlock`), causing a crash on startup.
2. **Registered accessor mixins** -- `MinecraftServerAccessor` and `SimpleRegistryAccessor` were missing from `customdimensions.mixins.json`, causing `ClassCastException` at runtime.
3. **RefMap included** -- proper Fabric Loom build generates the mixin refMap automatically.

## Building

```bash
# Generate Gradle wrapper
gradle wrapper --gradle-version 8.13

# Build
./gradlew build

# Output JAR at build/libs/customdimensions-1.0.1-fork.jar
```

## License

MIT
