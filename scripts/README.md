# Repository tools

Run tools from the repository root with JDK 25. Start with `./gradlew build`;
most Java tools use the resulting `build/tortodrone.jar`. Ordinary server hosting
does not need these tools. Build tasks already invoke the preparation helpers.

Python content tools require Python 3 and PyYAML. The optional artwork optimizer
requires Pillow with WebP support. These are not server runtime dependencies.

## Find the right tool

| Task | Tool | Instructions |
| --- | --- | --- |
| Check installed opening maps without starting a server | [CheckOpeningDeployment.java](CheckOpeningDeployment.java) | [Crash Site setup and playtest](../docs/content.md#local-crash-site-playtest) |
| Import an original scene into a stopped deployment | [MapImport.java](MapImport.java) | [Native map import](../docs/content.md#native-map-import) |
| Inspect/export stock configuration or run a native compatibility probe | [StockTools.java](StockTools.java), [compat/](compat/) | [Stock client compatibility](../docs/development.md#stock-client-compatibility) |
| Regenerate item and box support inventories | [index_item_support.py](content/index_item_support.py), [index_box_support.py](content/index_box_support.py) | [Items and weapon controls](../docs/content.md#items-and-weapon-controls); read each script's options first |
| Prepare optional WebUI artwork locally | [StockTools.java](StockTools.java), [OptimizeWebUiAssets.py](OptimizeWebUiAssets.py) | [WebUI verification](../docs/development.md#webui-verification) |

For the usual opening-content check:

```sh
java -cp build/tortodrone.jar scripts/CheckOpeningDeployment.java build
```

A passing check validates map identity, entrances and route prerequisites. It
does not play the tutorial. Follow the linked manual procedure for gameplay.

## Specialist tools

- `compat/` contains read-only native readers/exporters and isolated network or
  graphical fixtures. Check the chosen tool's source and usage before running:
  some launch clients or temporary servers and require exact scene revisions.
- `PrepareOpening.java` is a legacy helper for importing opening maps into a
  base without them. Normal builds and releases now bundle the opening maps;
  this helper is not needed for setup. Use `MapImport.java` only for additional
  scenes or an explicitly prepared content candidate.
- `CheckGeneratorControls.java` and `CheckChasm.java` are original-map component
  diagnostics. They do not prove a complete mission playthrough.
- `BuildTools.java`, `BuildToolsTest.java` and `ProcessCleanupTest.java` support
  Gradle preparation and regression tasks; prefer those tasks over invoking
  these helpers directly.
- Other scripts under `content/` reconstruct or import specific source data.
  They are maintainer tools, not server startup steps. Review their inputs,
  output paths and pinned evidence before publishing a content candidate.

Keep generated exports, captures, accounts, keys and proprietary artwork out of
Git. Use separate disposable directories for tests, and never point a fixture
at live saves or the owner's client preferences. Original scene data must be
available separately; tools do not supply missing assets or establish rights to
redistribute them. Background limitations are in
[Content](../docs/content.md) and [licensing](../docs/provenance.md).
