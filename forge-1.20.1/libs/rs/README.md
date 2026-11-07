# Refined Storage 1.x compile-time API

`refinedstorage-api-1.12.4.jar` is used with `compileOnly` so the storage binder can reach a
Refined Storage network on 1.20.1. It is not shipped with VeinMinerPlus, and the mod still loads
and runs without Refined Storage installed.

Refined Storage 1.x never published a separate API artifact, so this jar is **the public `api`
package only**, extracted from the official 1.12.4 release jar: 84 classes, no implementation,
no assets, no registries.

Upstream:

- Version: 1.12.4 (the last 1.20.1 release)
- Source: <https://github.com/refinedmods/refinedstorage>
- Download: <https://modrinth.com/mod/refined-storage/version/1.12.4>
- Release jar SHA1: `014bf4de4975f6310d22a6ed06c6a7e1e0805641` (3300191 bytes)

## Regenerating this jar

Download the official release jar, verify its SHA1, then keep only the public api package:

```bash
curl -L -o refinedstorage-1.12.4.jar \
  https://cdn.modrinth.com/data/KDvYkUg3/versions/ZITLFjjf/refinedstorage-1.12.4.jar
sha1sum refinedstorage-1.12.4.jar   # 014bf4de4975f6310d22a6ed06c6a7e1e0805641
```

Then, with Python, copy every `com/refinedmods/refinedstorage/api/**.class` entry out of it into
a new zip named `refinedstorage-api-1.12.4.jar`. Nothing else is needed: the binder only uses
`INetwork`, `INetworkNode`, `INetworkNodeProxy`, `Action` and their supporting types.

## Which types are used

| Type | Purpose |
| --- | --- |
| `api.network.INetwork` | `insertItemTracked(ItemStack, int)` returns the remainder; `canRun()` gates on energy and redstone |
| `api.network.node.INetworkNode` | `getNetwork()`, null when the node is not connected |
| `api.network.node.INetworkNodeProxy` | implemented by every RS block entity, used to recognise an RS block |

The full release jar is deliberately **not** committed: it is somebody else's complete mod binary
(assets, recipes, implementation included) and this repository is public.

Refined Storage is MIT licensed; see `LICENSE.md` in this directory.
