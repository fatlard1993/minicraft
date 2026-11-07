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
lamp beside it and going dark when thrown back; and a cupboard from one built-in chest, refusing a
second container, a shulker box and another cupboard, keeping its diamonds through being broken
and placed again, and refused by the bench while it still holds them. The handle is marked in the
workshop with a stick kept in the builder's pack beside the way out. A chest built into the box
stays shut in the workshop, so no material can be put away in it.

Known limits of the behaviours as built:

- A miniature is powered from underneath while it is tried: the floor under the box becomes
  redstone blocks. A circuit has to reach the floor to respond.
- A miniature with both a handle and a response to redstone powers whatever is beside it while
  its handle is on, and that can power it back, so it shows its powered look whenever the handle
  is on.
- Hoppers do not reach a miniature's storage.
- Storage is the chest or barrel's 27 slots, whichever it is, and one to a miniature.

Pandorical 15.27.0 carries the two extensions, each a new channel behind a capability, each with
its frozen byte sample, and its full suite passes: `scaled_structures` (`StructureApi.setScale`)
and `block_shapes` (`PandoricalApi.blockShapes()`, answered on the client by one mixin on a block
state's shape and collision, which costs one flag check while no shapes are set).

Step 9: block-tip's card for a miniature (its N, who made it, what it does) and for a bench (its
size, its materials, whether it is in use); the maker recorded on the item as it is finished;
textures for the bench (a ruled cutting mat on spruce, a drawer in each side) and the miniature
item (a cottage on a plinth), drawn by `generate_textures.py` from vanilla's own; the mod icon;
and a README covering the loop, the behaviours and the limits. The card is untested in a run:
block-tip is not on the gametest classpath.

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
