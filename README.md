# Smear

**A spectral processor - freeze, blur, scramble, shift, comb - running anywhere a UGen does, with a window.** A SuperCollider extension.

```supercollider
s.boot;
Ndef(\voice, { Smear.ar(Saw.ar([110, 111]) * LFPulse.kr(2).lag(0.01) * 0.2, \smear) }).play;

m = Smear(Ndef(\voice), \smear);
m.gui;
m.freezeOn = true;                  // hold it
m.freezeOn = false;
m.preset = 'glass choir';
m.smear = 12; m.shift = 16;
```

Every effect of this family lives in the same three places. Write `Smear.ar` into a graph of your own and drive it from the object, as above; give it a node of its own on a bus, `Smear.onBus(0, 2).enabled_(true)`; or put it in a chain, `FxChain.master.add(Smear())`. If SuperDirtGUI is installed, it is an insert on an orbit too: `~sdg.addInsert(0, \smear)`.

It is built on [FxKit](../sc%20fx%20kit), which is built on [ParaEQ](../sc%20EQ), and the class library will not compile without them; the installer links both when the folders sit side by side. It uses SuperCollider's own UGens, not sc3-plugins.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-smear.git");
thisProcess.recompile;
```

The manifest names `sc-fx-kit` as a dependency, which names `sc-eq`, so `Quarks` fetches those first. The quark goes by the name of the repository, `sc-smear`; the class inside it is `Smear`.

### From a clone

Clone `sc-eq` into a folder called `sc EQ`, `sc-fx-kit` into `sc fx kit` and this into `sc smear`, side by side. Open `install.scd` and evaluate its first block, then recompile the class library. It links what is missing and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the three folders into the extensions folder by hand: `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a duplicate class error at compile time.

### Check that it took

```supercollider
Smear.presetNames;
Smear.onBus(0, 2).enabled_(true).gui;
```

Select `Smear` and press cmd/ctrl + d for the help file, and see [examples/smear-demo.scd](examples/smear-demo.scd) for a walkthrough you can evaluate a block at a time.

## What it does

The sound is taken apart with an FFT and bent, all of it with SuperCollider's own PV UGens. In order: hold the spectrum (`freezeOn`, or stepped by `hold`), move it (`shift`), average neighbours (`smear`), swap bins (`jumble`), take random bins away (`holes`), take evenly spaced teeth away (`teeth`), pass only what is over a level (`gate`), and cut from one end (`brick`). All neutral, the wet is the input to within 0.01 dB.

Two of those needed more than the UGen. `PV_MagFreeze` holds magnitudes but takes its phases from the input, and silence has none to give, so a held note would turn into a buzz at the frame rate. While held, the FFT hears the last moment of sound looped instead. `PV_BinShift` on its own cancels against itself, 66 dB down on a sine, so a phase correction goes with it and `shift` moves in whole bins, about 23 Hz each.

`warp` multiplies every partial, which is a pitch shift. It is `PitchShift` ahead of the FFT, so it keeps the shift's own graininess and adds 100 ms; a pure tone comes out about 5 dB down with sidebands around it.

It costs the FFT's latency, 2048 frames less a block: about 41 ms at 48 kHz. The dry is delayed by as much, so `mix` lines up, and the latency stays while it is in the graph, bypassed or not.

## The window

`m.gui` opens it (`m.panel(view)` puts the same thing in a view of yours): the top row (on, presets, save, the menu with copy and paste), the display with the in and out meters beside it, and two pages of cards. Hover over anything and the bar at the bottom says what it does.

The display is the spectrum, 20 Hz to 20 kHz along, with each of Smear's operations drawn where it acts on it. Top to bottom:

- **the caption** - frozen, stepped (with `hold`) or flowing, then what is on, and at the right what it costs: the FFT's latency at the server's rate.
- **partials** - the first 48 partials of a 110 Hz note going in, in blue, and where Smear puts them, in pink: moved by `warp` and `shift`, thrown about by `jumble`, widened into a band by `smear`, hollow where `holes`, the comb or the brick wall take them away. It is a picture of the settings, not a measurement; jumble and holes are drawn again at their own rates, so they flicker as the sound does. Frozen, warp has nothing to move, and the lane says so.
- **the comb** - the line between the two: a notch for every closed tooth, moving with the server's own phase while it runs.
- **spectrum out** - the output's spectrum, in blue while it flows, the last few readings stacked faintly behind it. Frozen, the picture taken as it froze stays steady in pink over the faint live output. The closed teeth, the side the brick wall takes and what is under the gate are shaded; the gate is a pink line with a handle at its right (parked at the bottom right while it is off), the walls are grips at either edge.

| in the display | does |
|---|---|
| click the spectrum | freeze, and let go (`freezeOn`) |
| drag along it | `shift`, shift for fine |
| drag it up and down | `smear` |
| the wheel on it | `jumble` |
| double click it | shift and smear back to 0 |
| drag along the partials | `warp`, shift for fine; the wheel a semitone; double click back to 1x |
| drag the gate's line or handle | `gate`, shift for fine; the wheel a dB; double click off |
| drag a wall's grip | the brick wall (`brick`), from the bottom or the top; double click takes it away |

A drag on the spectrum moves one thing: the first few pixels choose along (shift) or up and down (smear).

Page **freeze & blur**: *freeze* - the freeze pad (click it, or hold it down to freeze only while you hold), hold - then *pitch* - shift, warp - and *blur* - smear, jumble, reach, shuffle, diffuse. Page **tear & cut**: *holes* - holes, flicker - *comb* - comb, open, sweep - and *cut* - gate, brick. The *output* card is on both: width, mix, output. What does nothing at the moment is dimmed: hold and warp while it is frozen, reach and shuffle with no jumble, flicker with no holes, open and sweep with the comb off.

## Presets

Built in: default, held breath, stepped ghost, melted, glass choir, bin shuffle, sinking, swiss cheese, sweeping comb, top partials.

Yours go in `Platform.userAppSupportDir/smear-presets.scd`: `m.saveAsPreset("mine")`, or press save in the window and give it a name. `m.copySettings` and `other.pasteSettings` carry settings from one to another.

## Parameters

Every parameter is a property: `m.freezeOn`, `m.freezeOn = ...`. These also work by name, `m.set(\freezeOn, ...)`, `m.get(\freezeOn)`. Knobs clip to their range. This table is generated from the class.

| parameter | section | range | default | what it does |
|---|---|---|---|---|
| `freezeOn` | freeze | on / off | false | hold the spectrum as it is now, for as long as the pad is lit - hold the pad down to freeze only while you hold it; or click the display |
| `hold` | freeze | 0 .. 30 Hz | 0 | take a new spectrum this often and hold it in between: a stepped, slowed down ghost of the input; 0 is off |
| `smear` | blur | 0 .. 64 bins | 0 | how many bins each side every bin is averaged with: partials melt into bands - or drag up and down in the display |
| `jumble` | blur | 0 .. 1 | 0 | how many bins swap places, at random - or the wheel in the display |
| `scrambleWidth` | blur | 0.01 .. 1 | 0.2 | how far a jumbled bin may land from where it was, as a share of the spectrum |
| `scrambleRate` | blur | 0.1 .. 30 Hz | 4 | how often a new jumble is drawn |
| `diffuse` | blur | on / off | false | new random phases twice a second: transients wash away |
| `shift` | pitch | -256 .. 256 bins | 0 | add this many bins to every partial, about 23 Hz each at 48 kHz: harmonics stop being harmonics - or drag along the display |
| `warp` | pitch | 0.25 .. 4 x | 1 | multiply every partial: a pitch shift, ahead of the freeze, with 100 ms of delay of its own - or drag along the partials in the display |
| `holes` | holes | 0 .. 1 | 0 | how many bins are taken away, at random |
| `holesRate` | holes | 0.1 .. 30 Hz | 2 | how often new holes are drawn |
| `teeth` | comb | 0 .. 48 teeth | 0 | how many teeth the comb takes out of the spectrum, evenly spaced; 0 is none |
| `combWidth` | comb | 0.02 .. 0.98 | 0.5 | how much of each tooth lets sound through |
| `combRate` | comb | 0 .. 8 Hz | 0.25 | how fast the teeth move up the spectrum; 0 holds them still |
| `gate` | cut | -96 .. 0 dB | -96 | only partials louder than this come through; -96 is off - or drag its line in the display |
| `brick` | cut | -1 .. 1 | 0 | a brick wall: under 0 it takes the top of the spectrum off, over 0 the bottom - or drag a wall in the display |
| `width` | output | 0 .. 2 | 1 | how far apart the sides are: 0 is mono, 100 % as it came |
| `mix` | output | 0 .. 1 | 1 | how much of the effect goes out, against the dry signal |
| `output` | output | -36 .. 24 dB | 0 | the level of the whole thing, in dB |

## Files

| file | what it is |
|---|---|
| [classes/Smear.sc](classes/Smear.sc) | the parameters, the presets, the DSP |
| [classes/SmearView.sc](classes/SmearView.sc) | the window's display |
| [HelpSource/Classes/Smear.schelp](HelpSource/Classes/Smear.schelp) | the help file |
| [HelpSource/Classes/SmearView.schelp](HelpSource/Classes/SmearView.schelp) | the help file for the display |
| [examples/smear-demo.scd](examples/smear-demo.scd) | a walkthrough, one block at a time |
| [install.scd](install.scd) | the symlink, a check, an uninstall |
| [sc-smear.quark](sc-smear.quark) | the quark manifest |

## License

MIT - see [LICENSE](LICENSE).
