# Minicraft - Plan

A bench that turns a full-size build into one placeable block: choose how finely a
block is cut, load materials, step into a bubble and build at full scale with exactly what you
loaded, then come out holding a block that is that build at a fraction of the size, solid where
it is solid.

## Decisions

- **Canvas size N** is the number of pieces along each edge of a block: 2, 3, 4 ... 16. Chosen at
  the bench, locked once the first material goes in.
- **Materials multiply by N³.** One block loaded is N³ cells of it, so a solid miniature of one
  material costs exactly one block. A finished miniature can never hold more material than went
  in.
- **Re-editable.** A finished miniature loaded back into a bench is rebuilt in the bubble, its
  cells already paid for.
- **A real collision shape**, from the cells: you stand on the seat of a miniature chair and walk
  under the arch of a miniature bridge.
- **Pandorical is required on clients**, as for Kragle and Amethyst Door: the miniature is drawn
  and given its shape by Pandorical. A client without it sees a coarse stand-in (below).

## The Loop

1. **Bench.** Place it, open it, pick N.
2. **Load.** Put blocks in. Each adds N³ cells to that material's budget. Blocks with a block
   entity (chests, signs, banners, heads, shulker boxes) are refused in the first version: a
   shulker box full of diamonds is the obvious way to smuggle anything through.
3. **Enter.** The player is taken to their own box: N x N x N of real blocks at full scale, in a
   frame they cannot break, in a workshop dimension. They fly, blocks break instantly, and the
   hotbar holds their materials.
4. **Build.** Placing spends a cell, breaking refunds one. That is all there is to it.
5. **Leave.** Back to the exact spot they stood on. The box stays as it was, so leaving is not
   finishing: they can come and go.
6. **Finalize.** At the bench. The box is read into a miniature item and cleared. Whatever budget
   is left stays in the bench.
7. **Withdraw.** Leftover budget comes out as whole blocks only, rounded down (cells / N³). The
   remainder stays in the bench for the next build. Rounding down is what makes the bench unable to
   create material.

## What Is Borrowed

| From | Piece | For |
|------|-------|-----|
| amethyst-door `Pocket.java` | One dimension cut into plots 512 apart, not a dimension each (dimensions cannot be added without a restart) | The workshop dimension and a box per player |
| amethyst-door `PocketVault.java` | Whose plot is whose, and the doorstep each player last stepped off | Coming back to where they stood, even if the bench was broken meanwhile |
| amethyst-door geode rules | A shell nothing breaks but creative | The box's frame |
| pvp-dimensions `arena/Visit.java` | The pack stashed in a persistent attachment on the player, beside the inventory, written together | Crash-safe enter and exit: nothing lost, nothing doubled |
| pvp-dimensions `arena/Borders.java` | A world border sent to one player, checked again on the server | Walls round the box |
| pvp-dimensions `arena/ModeChanges.java` | Changing mode and emptying the pack on the way in and out | Entering and leaving |
| pvp-dimensions `ui/BlockPicker.java` | A searchable grid of blocks | Picking among loaded materials past the hotbar's nine |
| bonsai-saplings `BonsaiTree.java` | Block displays scaled to a cell, displays as the record, a solid backing inside leaves | The stand-in for clients without Pandorical, and the first renderer while the Pandorical one is built |
| Pandorical `StructureApi` (big-boats' ships) | One anchor entity, a list of blocks, drawn by the client as one object | The real renderer, once it can scale |
| knick-knacks `Performance.java` | The tick-time bench | Keeping miniatures cheap |

## The Bubble

**Not creative mode.** Its inventory is infinite. The player is in survival with:

- flight allowed and on;
- instant breaking, by mixin, in the workshop dimension only;
- the hotbar holding one stack per material, each a window on that material's budget: it shows
  up to a stack's worth and is topped up from the budget as it is spent, so a budget of thousands
  of cells never needs thousands of items;
- dropping, throwing and opening containers refused;
- the pack stashed on the way in (Visit's attachment) and emptied and restored on the way out.

**The budget is the record, not the hotbar.** Placing a block in the box takes a cell off the
server's count; a player breaking one puts it back. Nothing picked up, dropped or exploded counts.

**The workshop dimension is held still**, so nothing makes or moves material on its own:

- no item entities are ever spawned there, so an explosion or a broken block drops nothing;
- no random ticks: a sapling cannot grow into a tree of free logs, ice does not melt, copper does
  not weather mid-build;
- the frame is immovable, so a piston cannot push a block out of the box;
- fire does not spread, and flint and steel is never in the pack to start one.

Scheduled ticks stay on: falling sand lands on the box's floor, and redstone works, which is half
the fun of a working miniature (it is captured as it stands).

## The Item

`minecraft:custom_data` holding N, a palette of block states, and the cells packed as indices into
it. 16³ is 4096 cells; packed, a few kilobytes at most, well inside what an item can carry. Plain
custom data rather than a component of our own, so the item travels through any container, trade
or hopper without a client needing to know a new component type.

Kept with it, so the bench and the block never need to work it out again: the cells that show
(interior cells dropped), and the shape as merged boxes.

## The Placed Block

A Pandorical-registered block with a block entity holding the item's data, facing whichever way
it was placed (four turns). Being a real block is what gives it a collision shape, a place on the
grid, Kragle's glue, block-tip's card, pick-block and pistons for free.

**Breaking it gives the item back**, data and all. That is also how a miniature goes back into a
bench to be edited.

**Drawn** by Pandorical's structure renderer, anchored to an entity spawned with the block (a
structure rides entity tracking, so the anchor must be an entity clients track; big-boats anchors
to its boat). Only the cells that show are sent.

**Light**: a miniature with glowstone or a lantern in it gives off light, scaled down by how much
of it glows, through the block's own light level.

## The Collision Shape

The cells merged greedily into as few boxes as will cover them, built once into a `VoxelShape`
and kept on the block entity, turned to the block's facing. The same shape is the outline, so what
you target is the model, not a cube round it.

**Client and server must agree** or a player rubber-bands off a seat that is solid on one side and
not the other. A Pandorical client builds its stand-in from a base vanilla block's class, so it has
no way to know a shape that differs from placement to placement. That needs a small Pandorical
capability, **`block_shapes`**:

- the server sends, for each placed miniature a client can see, its position and its boxes, as it
  comes into view and whenever it changes;
- the client's stand-in answers collision and outline from that map for those positions, and
  from a full block until the data arrives;
- the server answers from the same shape for players with the capability, and a full block for
  any without it, so nobody's prediction is ever wrong.

Collision is asked for constantly, so both sides keep the built shape and never rebuild it per
query.

## The Pandorical Extensions

Both small, both behind capabilities so an older client is simply sent nothing, both a Pandorical
version bump that this mod then requires:

1. **Scaled structures.** `StructurePose` gains a scale. In the client's `StructureRenderer` it is
   one `poseStack.scale` after the rotation, plus taking light from the anchor's block rather than
   each block's scaled-down position (which would sample the wrong cells entirely). Capability
   `scaled_structures`.
2. **Block shapes**, as above.

The structure renderer submits every block every frame. That is fine with interiors culled and a
modest number of miniatures in view; if rooms full of them get common, the next step is caching
each structure's mesh rather than resubmitting it.

## Clients Without Pandorical

They see block displays, one per showing cell, the bonsai way, capped (say 256 per miniature, the
smallest cells dropped first if over), and collide with a full block. Good enough to tell what it
is; not the intended look.

## Behaviours: Redstone, A Handle, Storage

A miniature is more than a look when its project is given **behaviours**, chosen on the bench as a
short set of options before finishing. Each is off unless chosen, and each is captured from the
build itself, so what a miniature does is what its full-size build did.

### Responds to redstone

For lamps, doors, pistons, anything that changes when powered. At finish the bench powers the
box from underneath (the floor under its footprint briefly becomes redstone blocks), lets the
circuit settle for a few ticks, and captures a **powered look**; then takes the power away, lets it
settle again, and captures the **unpowered look**. A placed miniature with both looks swaps
between them when any neighbour powers it, exactly as a block reacts to a neighbour's signal.

Its light follows its look: the brightest block in the current look sets the block's light level,
so a miniature redstone lamp lights the room when the main world's lever is thrown, the way a
full-size one would.

### A handle

For a lever, a button, a door to open by hand. The builder marks one cell in the box as the handle
(right-click it in the workshop with the way out, or pick it on the bench). At finish the bench
flips that cell and captures a look for each of its positions. Right-clicking the placed miniature
works the handle: the look changes, and while the handle is on, **the miniature gives off a
redstone signal**, so a miniature lever really is a lever, wired into the world around it.

Combined with responding to redstone, a miniature has up to four looks: powered or not, handle on
or off. That is the limit, and it keeps the capture a fixed amount of work.

### Storage

For a cupboard, a chest of drawers, a crate. The builder may load one container (a chest, a barrel)
as material, empty, and its cell becomes the storage. The placed miniature holds that container's
slots, opened by right-clicking it, and keeps its contents when broken, the way a shulker box does:
a miniature with storage, or a shulker box, cannot be put inside one.

With the handle as well, the handle is worked with a sneaking right-click and the storage opens on
a plain one. Once the collision shape is exact, a click lands on a cell, and the handle and the
storage are each worked by clicking their own part of the miniature.

### Why looks, not a living circuit

A miniature cannot run its circuit live: every copy placed would need a full-size box of its own
somewhere, ticking. Looks captured at finish cover what people want from a lamp, a door, a lever
or a cupboard, at the cost of a few extra cell arrays on the item, and a placed miniature costs
nothing until its neighbour changes. Clocks, counters and anything that keeps its own state over
time are out of reach this way; they stay full size.

## Exploits Closed By Design

- **Material from nothing**: N³ in, cells out, whole blocks only when withdrawn, rounded down.
- **Smuggling**: block-entity blocks refused as materials; the pack emptied on exit; no item
  entities in the dimension.
- **Doubling on a crash**: the stash and the inventory saved together on the player (Visit).
- **Growth and decay**: no random ticks in the workshop.
- **Escaping the box**: border sent and enforced, frame unbreakable and immovable.
- **Stranding**: back to the doorstep, not the bench, which someone else may have broken.

## Build Order

Each step ends playable and tested.

1. **Scaffold**: gradle and Pandorical setup from amethyst-door, gametest setup from knick-knacks.
2. **Workshop and box**: the dimension, plots (from Pocket and PocketVault), entering and leaving
   with the stash (from Visit), the border, flight, instant breaking, no drops, no random ticks,
   the immovable frame. A command to enter stands in for the bench.
3. **Bench and budget**: the block, its screen, choosing N, loading, the hotbar windows, the budget
   counting places and breaks, withdrawing rounded down.
4. **Capture, the item and the placed block**, drawn with displays for now, with a full-block
   shape. The whole loop works end to end from here.
5. **Re-editing**: a miniature into a bench, rebuilt in the box, finalized again.
6. **Pandorical scaled structures**: the API change, the client renderer, the capability; switch
   the miniature to it and keep the displays for clients without.
7. **Pandorical block shapes**, and the real collision and outline.
8. **Behaviours**: the options on the bench, responding to redstone (two looks, light from the
   look), the handle (looks per position, a signal while on), storage (one empty container loaded,
   slots on the placed miniature, kept when broken).
9. **Finishing**: block-tip's card (what it is, its N, who made it), pictures, the README.

## Status

All nine steps are built. Steps 1 to 7 pass the loop gametest (`BenchLoop`): sizing, loading, entering and
leaving with the pack untouched, placing and breaking against the budget, the box held to its
edges, no drops, no random ticks, finishing, placing, breaking back to the item, withdrawing whole
blocks, editing a miniature again, a broken bench giving up its build; drawn by Pandorical as a
scaled structure with no figures sent to that client; and a quarter-block floor that is a quarter
of a block on the server and the client, and stood on at that height.

Step 8, the behaviours, passes its own gametest (`Behaviours`): a lamp captured lit and dark that
follows the world's redstone and gives off light only while lit; a repeater relay captured right
with nobody in the workshop (the box's chunk is kept ticking while it is tried, and the test fails
without that); a lever marked with the stick, thrown by a right-click on the miniature, lighting a
lamp beside it and going dark when thrown back. The handle is marked in the workshop with a stick
kept in the builder's pack beside the way out.

Storage from a chest built into the box is gone; the bench has a **functional slot** instead, and
the finished miniature works as the block put in it (`Functions`, one small kind per sort of
block, each making the `Function` a placed miniature holds). Menus that keep nothing (crafting
table, stonecutter, loom, smithing table, grindstone, cartography table, enchanting table counting
real bookshelves, ender chest, the anvils, which wear through as an anvil does); 27-slot storage
(any chest, barrel, shulker box; a trapped one signals while open); and the furnaces, brewing stand
and jukebox, each a real vanilla block entity the miniature holds, ticks and saves, opened with
vanilla's own menu. Its things and progress ride on the item in custom data beside the build
(`function_state`), so they survive breaking and placing. Five small mixins let vanilla menus stay
open at a spot that holds a miniature rather than the block, keep a held furnace from setting a
furnace into the world as it lights, and hold every plain slot over something a miniature keeps
to "no box in a box". Hoppers and comparators reach what a miniature keeps. A miniature from
before the slot, with storage counted as slots and its things in a container component, is read
back as a chest (or barrel) function holding them.

**Slab format**, chosen with the size: an even N, a box N/2 high, a miniature half a block high on
the half clicked. The cells are stored as before with an optional `height` beside `n` (absent for
a block). **Majority material**: a placed miniature breaks, is quicker with the tool of, and sounds
like the block filling most of its build as built; with the wrong tool it is as slow as that block
is, and still drops whole, data, name and contents kept. The breaker's client is kept in step by
setting their own break speed attribute while they break one (`Breaking`), and told the majority
block as the position's material (`PandoricalApi.blockMaterials()`), so its own steps, landings,
hits, cracks and break sound and crumble as the build; the server plays the build's sounds and
dust to everyone else. **Names**: typed on the bench, carried
as the item's custom name, kept through placing and re-editing, used for menu titles. **Facing**:
a new miniature is placed as built, never turned to its placer; facing stays in the state so
miniatures placed before keep their turn.

`Behaviours` covers these too: the slot's refusals (a beacon, a lectern, stone, a chest with
something in it) and giving a block back; a crafting-table miniature opening a crafting menu that
stays open; a furnace miniature smelting while placed, staying a miniature while lit, keeping its
things and progress through being broken and placed and finishing where it left off, refused by
the bench while full and handing its furnace back to the slot once empty; a named chest miniature
keeping three diamonds and its name through three rounds of breaking and placing with none spilled,
its slots refusing a shulker box; a mostly-stone miniature breaking as stone does, slower by hand
than with a pickaxe, and by hand still dropping whole with its build, name and diamonds, none
spilled; a mostly-wool one sounding and breaking like wool; and a slab: half a block of
material for a solid slab, nothing whole withdrawn from the half left, nothing placed above its
box, bottom and top placement with the right shape, and the slab taken apart again giving back
its one block exactly.

**Copying** (`Copying`, and the Copy tab in `BenchScreen`): the bench screen has two tabs, Build
and Copy, swapped in place with Pandorical's `visible` prop rather than reopened. Pandorical has no
tab component, and a hidden `inventory_grid` still leaves its vanilla slots where it put them, so
each grid not on the tab shown is also moved far off the screen, slots and all (one grid per slot
set, moved, never two grids for one slot). The menu has three server slots: the functional slot (a
window on the ledger, as before), the copy's input (the viewer's own, given back in
`Container.stopOpen` as vanilla gives back a crafting grid, dropped if they have gone), and the
output. The output is worked out every time it is asked for: it shows only while the Copy tab is up,
the bench still stands with its viewer in reach, nobody is in its box or trying a build out, every
material is held and any block needed is in the functional slot. The cost is the original's look as
built, counted by the same state to material mapping as building and breaking in the box
(`Visits.materialOf` and `blocksIn`, a door's or tall plant's upper half and a bed's head counted
with their other half), converted to the bench's size per material as `ceil(cells * Nbench^3 /
Nmini^3)`. Taking it charges in one step, checked again at that moment: through `removeItem` for a
click, a drop or a double click, and through `setItem(EMPTY)` for a shift-click or number key,
which move the shown stack first and empty the slot after (should that ever find the bench unable
to pay, the copy is taken back off the viewer). One copy a tick, so a shift-click takes one. The
copy is made fresh from the looks, function and name, so nothing the original holds comes with
it; an anvil function needs any anvil and takes the spent one's wear. A screen whose bench is gone
or whose viewer has walked off is closed four times a second, which gives the input back. Making
the mapping one function for building and copying also gave a potted plant back its pot, and a
candle cake its cake, when broken in the box: both were charged as placed and refunded nothing.

`Behaviours` covers copying through the bench's own menu, opened by a right-click and clicked with
`AbstractContainerMenu.clicked` as a click packet arrives: a shed of two planks and a door built
from the pack costs exactly that, and its copy charges the same (two planks, one door) with the
shed left in the slot and its name and maker carried; a shift-click takes one copy and charges
once; no door, no copy, and a click takes and spends nothing; a copy shown, then the door drained
before it is taken, gives nothing and charges nothing by click, shift-click, number key or a direct
take; a bench with somebody in its box shows no copy; closing the screen gives the shed back; a
build cut 3 to a side copied at a bench cutting 4 costs 3 stone for 1 cell and 12 cobblestone for
5; a chest-working miniature holding three diamonds needs a chest, and its copy works as a chest,
holds nothing and keeps its name while the original keeps its diamonds and the chest is spent; a
chipped anvil serves for an anvil and the copy is chipped; and breaking the bench with the screen
open closes it and gives the original back. Not clicked through: the tab buttons are pressed by
calling `BenchScreen.showTab` (what their action handler calls) rather than sending the button's
packet, and two players at one bench is not tested, as the test has one client; it rests on the
take being checked against the shared ledger and functional slot at the moment of taking.

**Building full size** (`Constructing`, `ConstructScreen`, `Preview`, `Transform`): a sneaking, empty-handed right-click on a
placed miniature opens a Pandorical dialog listing its cost at full size (`Copying.cells` with no conversion: one block a
cell, a door once, a double slab two) against the player's pack, hotbar and off hand, with Construct, Preview, and the
turn, mirror, upside-down and reset buttons. The anchor never turns: the miniature's block is cell (0, 0, N-1), so the build
runs east, north and up; a transform turns the content inside the same N by N box. Upside down is done by property (halves,
slab type, vertical facings, attach face, hanging, vertical direction, up and down sides); a block with none whose shape is not
the same either way up is kept as it is and counted, two-high blocks are kept upright, and if a kept block could not survive
where it lands (asked of the world as it would be, through `Overlay`) the construct is refused. The transform is kept on the
block entity only. Construct surveys again on the server at that moment: every non-air target air or replaceable and free
of entities that block building, inside the build height and world border, not under spawn protection, `mayBuild`, not in
the workshop, Pandorical trust for fire, lava and TNT, every chunk loaded; then the materials are taken all or none (none in
creative), the miniature's own loot is given back (inventory, else at the feet), the blocks are set with
`UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` as `Miniature.build` does, and the build is settled once as vanilla settles a placed
structure: the world beside each edge face updated against the build, each block's shape against its neighbours (through
`updateOrDestroy`, so anything unsupported drops as an item rather than vanishing), and `updateNeighborsAt` for every block.
One place sound. The preview is particles to that player alone twice a second: white dust along the box's edges, green dust on the
footprint's outline at the base, red dust on every spot in the way; it stops on a toggle, past 32 blocks, another dimension,
the miniature gone, or two minutes.

**A handle or a function, never both.** `Looks.hasHandle` is false whenever a function is present, so an older miniature with
both keeps its function and contents and ignores its handle. The bench refuses a block in the functional slot while a handle is
marked, the stick refuses to mark one while the slot holds a block, finishing refuses both, and re-editing brings back only the
function. Clicks: sneak with an empty hand opens the dialog; otherwise a handle miniature works its handle on any click (a
sneaking click with a non-block item through Fabric's use event, the item's use in the air that tick refused), a function
miniature opens its function, and a sneaking click with a block places it as vanilla does. On entering the box the first three
times (until a handle is marked), three chat lines explain the handle; the stick carries a name and lore saying the same.

`Behaviours` covers it (`oneOrTheOther`, `fullSize`, `transforms`, `handles`), driven through `useItemOn` with the player
sneaking and the dialog's buttons pressed through `ScreenRegistry.handleAction`, the server's handler for a button's packet:
the cost equals the copy's; short, refused with nothing taken or placed; dirt in the way refused and found, grass not; the door
drained after the dialog showed ready, refused; everything carried, built at the anchor with the corner, the far north-east top,
the door's halves, the stair and the slabs checked by position, charged exactly, the miniature back with its name, build and
diamonds; creative charged nothing; a slab built two high; a quarter turn, a mirror and upside down each placed where expected
with stairs and slabs turned, the turn kept through closing and reopening; an upside-down torch refused until reset; a handle
worked by a plain click and a sneaking stick, not by a sneaking empty hand (which opens the dialog), and a sneaking cobblestone
placed on top; an old handle-and-chest opening as a chest and keeping its diamonds; the bench refusing a handle and a function
together each way round and at finish. Not clicked through: the buttons' packets are not sent from the client (the handler is
called with the packet the button would send), and the Construct button's disabled look is not checked on the client.

Known limits of the behaviours as built:

- A miniature is powered from underneath while it is tried: the floor under the box becomes
  redstone blocks. A circuit has to reach the floor to respond.
- A miniature with both a handle and a response to redstone powers whatever is beside it while
  its handle is on, and that can power it back, so it shows its powered look whenever the handle
  is on.
- Some blocks cannot be worked as and are refused in the slot: beacon, lectern, campfire, crafter,
  dispenser, dropper, hopper, bell, note block, composter, cauldron, bed, respawn anchor, decorated
  pot, chiseled bookshelf, shelf. Each either draws its work on the block itself or works through
  its own block state, which a miniature does not have.
- A working miniature does not show it: no lit furnace face, no opening lid.
- Slab miniatures do not join into a double.

Pandorical 15.27.0 carries the two extensions, each a new channel behind a capability, each with
its frozen byte sample, and its full suite passes: `scaled_structures` (`StructureApi.setScale`)
and `block_shapes` (`PandoricalApi.blockShapes()`, answered on the client by one mixin on a block
state's shape and collision, which costs one flag check while no shapes are set).

Pandorical 15.30.0 adds a third, `block_materials` (`PandoricalApi.blockMaterials()`): the block
state a position sounds and crumbles as, set from the miniature's majority block beside its shape
and cleared with it, and answered on the client by small mixins on the step, landing, mining and
break lookups, each one flag check while no material is set.

Step 9: block-tip's card for a miniature (its N, who made it, what it does) and for a bench (its
size, its materials, whether it is in use); the maker recorded on the item as it is finished;
the bench's look (a spruce worktable three quarters of a block high, a ruled cutting mat on top
and a drawer in each side, with an iron arm from a back corner holding vanilla's spyglass over the
mat like a loupe; its shape is the 12-pixel body, which Pandorical sends to clients with the
content sync) and the miniature item (a cottage on a plinth), drawn by `generate_textures.py` from vanilla's own; the mod icon;
and a README covering the loop, the behaviours and the limits. The card is untested in a run:
block-tip is not on the gametest classpath.

The miniature item draws its own build on a Pandorical 15.30 client: `pandorical/items/miniature.json`
is a `pandorical:cells` model reading the `miniature` key, which such a client loads in place of
`items/miniature.json`; the cottage stays the plain model, for older clients and for a stack with
no build. Pandorical's `CellsItems` gametest pictures it.

A miniature moved by something that neither places nor breaks it keeps its drawing with it. Big
Boats takes the block entity first and clears the block with no neighbour update, so neither removal
hook runs, and docking sets the block and loads the block entity back without placing it. Each tick,
a miniature block entity removed since the last one has its drawing cleared if the block there is no
longer a miniature (a chunk unloading never changes it), and one that arrived is drawn once its
chunk's entities are in and nothing of its drawing is there. `Behaviours.shipped` makes both moves.

Where the build differs from the plan so far:

- No world border yet: a builder who strays past the bounds is brought back to the arrival spot,
  which works on any client. The per-player border from pvp-dimensions is still the nicer edge.
- No block picker yet: the pack shows up to 35 materials, which is plenty for most builds.
- The box's edges are drawn with motes once a second, for the builder alone.

## Tests

Client gametests, as knick-knacks does, through the server's own click and packet paths:

- the budget arithmetic: N³ per block loaded, one per place and break, withdrawal rounded down,
  and never more out than in;
- entering and leaving: the pack the same before and after, flight off again, and the stash
  surviving a save and reload mid-visit;
- the box held still: nothing drops, a sapling does not grow, a piston does not push past the frame;
- capture round trip: a build captured, placed, broken and re-entered comes back cell for cell;
- refusal: a shulker box will not load; an item thrown inside does not leave the dimension;
- the collision shape: a player stands on a miniature slab at its height, and walks through where
  it is empty;
- pictures of a few miniatures in a room, to look at by eye;
- the knick-knacks tick bench, pointed at a room of miniatures.

## Open For Later

- Block-entity blocks as materials, with their contents dropped and only their look kept.
- Fluids: water and lava in a miniature, captured as waterlogging and fluid blocks.
- Ceilings and walls: miniatures placed hanging, as knick-knacks does.
- Sharing: a miniature's data as a file, the way pvp-dimensions presets travel between servers.
