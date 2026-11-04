# Smear

**A spectral processor - freeze, blur, scramble, shift, comb - running anywhere a UGen does, with a window.** A SuperCollider extension.

```supercollider
s.boot;
Ndef(\voice, { Smear.ar(Saw.ar([110, 111]) * LFPulse.kr(2).lag(0.01) * 0.2, \smear) }).play;

m = Smear(Ndef(\voice), \smear);
m.gui;
m.frozen = true;                  // hold it
m.frozen = false;
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

The sound is taken apart with an FFT and bent, all of it with SuperCollider's own PV UGens. In order: hold the spectrum (`frozen`, or stepped by `hold`), move it (`shift`), average neighbours (`smear`), swap bins (`scramble`), take random bins away (`holes`), take evenly spaced teeth away (`teeth`), pass only what is over a level (`gate`), and cut from one end (`brick`). All neutral, the wet is the input to within 0.01 dB.

Two of those needed more than the UGen. `PV_MagFreeze` holds magnitudes but takes its phases from the input, and silence has none to give, so a held note would turn into a buzz at the frame rate. While held, the FFT hears the last moment of sound looped instead. `PV_BinShift` on its own cancels against itself, 66 dB down on a sine, so a phase correction goes with it and `shift` moves in whole bins, about 23 Hz each.

`stretch` multiplies every partial, which is a pitch shift. It is `PitchShift` ahead of the FFT, so it keeps the shift's own graininess and adds 100 ms; a pure tone comes out about 5 dB down with sidebands around it.

It costs the FFT's latency, 2048 frames less a block: about 41 ms at 48 kHz. The dry is delayed by as much, so `mix` lines up, and the latency stays while it is in the graph, bypassed or not.

## The window

`m.gui` opens the top row (on, presets, save, copy, paste), the display, and a row of knobs per section. Hover over anything and the bar at the bottom says what it does.

In the display, drag up and down for the smear; the wheel for the stretch.

## Presets

Built in: default, held breath, stepped ghost, melted, glass choir, bin shuffle, sinking, swiss cheese, sweeping comb, top partials.

Yours go in `Platform.userAppSupportDir/smear-presets.scd`: `m.saveAsPreset("mine")`, or type a name in the window and press save. `m.copySettings` and `other.pasteSettings` carry settings from one to another.

## Parameters

Every parameter is a property: `m.frozen`, `m.frozen = ...`. These also work by name, `m.set(\frozen, ...)`, `m.get(\frozen)`. Knobs clip to their range. This table is generated from the class.

| parameter | section | range | default | what it does |
|---|---|---|---|---|
| `frozen` | time | on / off | false | hold this spectrum, for as long as it is on |
| `hold` | time | 0 .. 30 Hz | 0 | take a new spectrum this often and hold it in between: 0 is off |
| `smear` | time | 0 .. 64 bins | 0 | how many bins each side are averaged in |
| `scramble` | time | 0 .. 1 | 0 | how many bins are swapped |
| `scrambleWidth` | time | 0.01 .. 1 | 0.2 | how far apart they may be |
| `scrambleRate` | time | 0.1 .. 30 Hz | 4 | how often a new order is drawn |
| `diffuse` | time | on / off | false | new phases, from time to time: transients wash away |
| `shift` | pitch | -256 .. 256 bins | 0 | add this many bins to every partial, about 23 Hz each: harmonics stop being harmonics |
| `stretch` | pitch | 0.25 .. 4 x | 1 | multiply every partial: a pitch shift, with its own 100 ms of delay |
| `holes` | pitch | 0 .. 1 | 0 | how many bins are taken away at random |
| `holesRate` | pitch | 0.1 .. 30 Hz | 2 | how often new ones are drawn |
| `teeth` | pitch | 0 .. 48 | 0 | how many teeth are taken out of the spectrum: 0 is none |
| `combWidth` | pitch | 0.02 .. 0.98 | 0.5 | how much of each tooth passes |
| `combRate` | pitch | 0 .. 8 Hz | 0.25 | how quickly the teeth move |
| `gate` | output | -96 .. 0 dB | -96 | only what is over this comes through: the loudest partials |
| `brick` | output | -1 .. 1 | 0 | under 0 takes the top of the spectrum off, over 0 the bottom |
| `width` | output | 0 .. 2 | 1 | how far apart the sides are |
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
