# Third-party notices

The SlashSlabs jar nests these unmodified libraries (Fabric jar-in-jar, `META-INF/jars/`):

| Library | Author | Licence | Source |
|---|---|---|---|
| Polymer (`polymer-core`, `polymer-blocks`, `polymer-resource-pack`, `polymer-sound-patcher`, `polymer-autohost`, and the modules they nest) | Patbox | LGPL-3.0 | <https://github.com/Patbox/polymer> |

Polymer is used as a library under the GNU Lesser General Public License v3.0
(`licenses/LGPL-3.0.txt`, which supplements `licenses/GPL-3.0.txt`). It is not modified. It stays
replaceable: Fabric Loader loads the newest copy of a nested mod, so a server can drop a different
Polymer build into `mods/` and SlashSlabs uses that one instead. Nothing in SlashSlabs' licence
restricts reverse engineering for debugging changes to Polymer.

Minecraft textures: the jar ships no Mojang pixels. Grass slab textures are generated at runtime
from the vanilla client jar Polymer downloads from Mojang, and the BlueMap models only reference
vanilla texture paths.
