// =====================================================================
// Smear - a spectral processor: freeze, blur, scramble, stretch, comb
//
// The sound is taken apart with an FFT and bent in the frequency domain, all of
// it from SuperCollider's own PV UGens, in this order:
//
//   freeze     hold the current spectrum; `hold` samples a new one every so
//              often, for a stepped, slowed down ghost of the input
//   shift      add to every partial, so harmonics stop being harmonics; and
//   stretch    multiply them, which is a pitch shift, ahead of the FFT
//   smear      average each bin with its neighbours: partials melt into bands
//   scramble   swap bins at random, `scrambleRate` times a second
//   holes      take random bins away
//   comb       take evenly spaced teeth away, sweeping at `combRate`
//   gate       only partials over a level
//   brick      a brick wall, from the top or the bottom
//
//   Ndef(\voice, { Smear.ar(Saw.ar([110, 111]) * LFPulse.kr(2).lag(0.01) * 0.2, \smear) }).play;
//   m = Smear(Ndef(\voice), \smear);
//   m.gui;
//   m.freezeOn = true;                    // hold it
//   m.preset = 'glass choir';
//
// It costs fftSize of latency - 2048 frames less a block, about 41 ms at 48 kHz
// - and the dry signal is delayed by as much, so mix lines up. That is there
// whatever the settings, while it is in the graph; bypass does not take it away.
// A stretch other than 1 adds 100 ms to the wet, and the dry does not wait for that.
// =====================================================================

Smear : FxParamUnit {
	classvar <>fftSize = 2048;

	*controlName { ^\smear }
	*hasTail { ^true }
	*displayClass { ^SmearView }

	// parameters renamed on 2026-11-08 so each is a free Tidal word (old -> new):
	// old presets, sessions and code still reach them
	*renamed { ^(scramble: \jumble, stretch: \warp, frozen: \freezeOn) }

	// what a Tidal pattern may move through SuperDirtGUI, event by event
	*tidalParams { ^#[\freezeOn, \hold, \smear, \jumble, \scrambleRate, \shift, \warp, \holes, \teeth, \combWidth, \combRate, \brick, \width, \mix] }

	*params {
		// labels, sections, sizes and hints are the window's (sc-fx-ui, 2026-11-09);
		// the order and the keys are the nodeMaps' - append, never reorder
		^[
			this.toggle(\freezeOn, false, "freeze",
				"hold the spectrum as it is now, for as long as the pad is lit - hold the pad down to freeze only while you hold it; or click the display",
				\freeze).put(\size, \big),
			this.knob(\hold, 0, 30, \lin, 0, "Hz", "hold",
				"take a new spectrum this often and hold it in between: a stepped, slowed down ghost of the input; 0 is off",
				section: \freeze, floorText: "off"),
			this.knob(\smear, 0, 64, \lin, 0, "bins", "smear",
				"how many bins each side every bin is averaged with: partials melt into bands - or drag up and down in the display",
				lag: 0, section: \blur, dec: 0, step: 1, size: \big),
			this.knob(\jumble, 0, 1, \lin, 0, "", "jumble",
				"how many bins swap places, at random - or the wheel in the display", box: \pct, section: \blur),
			this.knob(\scrambleWidth, 0.01, 1, \lin, 0.2, "", "reach",
				"how far a jumbled bin may land from where it was, as a share of the spectrum", box: \pct, section: \blur, size: \small),
			this.knob(\scrambleRate, 0.1, 30, \exp, 4, "Hz", "shuffle",
				"how often a new jumble is drawn", section: \blur, size: \small),
			this.toggle(\diffuse, false, "diffuse", "new random phases twice a second: transients wash away", \blur),

			this.knob(\shift, -256, 256, \lin, 0, "bins", "shift",
				"add this many bins to every partial, about 23 Hz each at 48 kHz: harmonics stop being harmonics - or drag along the display",
				lag: 0, section: \pitch, dec: 0, step: 1, size: \big),
			this.knob(\warp, 0.25, 4, \exp, 1, "x", "warp",
				"multiply every partial: a pitch shift, ahead of the freeze, with 100 ms of delay of its own - or drag along the partials in the display",
				section: \pitch),
			this.knob(\holes, 0, 1, \lin, 0, "", "holes", "how many bins are taken away, at random", box: \pct, section: \holes),
			this.knob(\holesRate, 0.1, 30, \exp, 2, "Hz", "flicker", "how often new holes are drawn", section: \holes),
			this.knob(\teeth, 0, 48, \lin, 0, "teeth", "comb",
				"how many teeth the comb takes out of the spectrum, evenly spaced; 0 is none", lag: 0, section: \comb, dec: 0, step: 1, floorText: "off"),
			this.knob(\combWidth, 0.02, 0.98, \lin, 0.5, "", "open", "how much of each tooth lets sound through", box: \pct, section: \comb),
			this.knob(\combRate, 0, 8, \lin, 0.25, "Hz", "sweep", "how fast the teeth move up the spectrum; 0 holds them still", section: \comb, floorText: "off"),

			this.knob(\gate, -96, 0, \lin, -96, "dB", "gate",
				"only partials louder than this come through; -96 is off - or drag its line in the display", section: \cut, dec: 0, floorText: "off"),
			this.knob(\brick, -1, 1, \lin, 0, "", "brick",
				"a brick wall: under 0 it takes the top of the spectrum off, over 0 the bottom - or drag a wall in the display",
				box: \pct, section: \cut),
			this.knob(\width, 0, 2, \lin, 1, "", "width", "how far apart the sides are: 0 is mono, 100 % as it came", box: \pct, section: \output),
			this.mixParams(\output)
		]
	}

	*presetList {
		^[
			\default, (),
			'held breath', (freezeOn: true, smear: 2, mix: 0.8),
			'stepped ghost', (hold: 6, smear: 3),
			'melted', (smear: 24, diffuse: true),
			'glass choir', (hold: 1.5, smear: 1, warp: 2, jumble: 0.05, mix: 0.7),
			'bin shuffle', (jumble: 0.6, scrambleWidth: 0.3, scrambleRate: 8),
			'sinking', (shift: -32, hold: 12, smear: 2),
			'swiss cheese', (holes: 0.7, holesRate: 5),
			'sweeping comb', (teeth: 12, combWidth: 0.4, combRate: 0.5),
			'top partials', (gate: -36, smear: 1, warp: 1.5)
		]
	}

	// ------------------------------------------------------------- the window

	// The family is the spectral freeze: the display is the spectrum coming out
	// on a log Hz ruler, each operation drawn where it acts on it (the comb's
	// teeth, the walls, the gate's line), the partials of a note above it moved
	// as the settings move them (SmearView); freeze is a big latch pad, smear and
	// shift the big knobs. Nineteen controls do not fit one row, so what tears
	// the spectrum (holes, comb, gate, brick) has a page of its own.
	*guiClass { ^FxDeviceGUI }
	*panelClass { ^FxDevicePanel }
	*windowSize { ^Point(960, 580) }

	// a held, moved spectrum: the frozen picture, the partials coming out, the hero knobs
	*identityColour { ^FxPalette.identity(\spectral) }

	*devicePages {
		^[
			'freeze & blur' -> [
				(title: "freeze", keys: [\freezeOn, \hold], colour: \identity),
				\pitch,
				\blur
			],
			'tear & cut' -> [\holes, \comb, \cut]
		]
	}

	// what does nothing in the current settings, dimmed
	*isActive { |key, unit|
		^switch (key,
			// frozen, nothing new is taken in: hold has nothing to step, warp nothing to move
			\hold, { unit.get(\freezeOn) != true },
			\warp, { unit.get(\freezeOn) != true },
			\scrambleWidth, { unit.get(\jumble) > 0 },
			\scrambleRate, { unit.get(\jumble) > 0 },
			\holesRate, { unit.get(\holes) > 0 },
			\combWidth, { unit.get(\teeth) > 0 },
			\combRate, { unit.get(\teeth) > 0 },
			{ true }
		)
	}

	// how far the wet is behind what came in, in seconds, given the server's
	// block and sample rate: what the dry is delayed by
	*latencySignal { ^(fftSize * SampleDur.ir) - ControlDur.ir }

	*process { |sig, p, ctx|
		var size = fftSize;
		var stretched = BinaryOpUGen('!=', p[\warp], 1);
		var held = (p[\freezeOn] + (BinaryOpUGen('>', p[\hold], 0)
			* LFPulse.kr(p[\hold].max(0.01), 0, 1 - (0.02 * p[\hold]).clip(0.005, 0.5)))).clip(0, 1);
		var scrambleTrig = Impulse.kr(p[\scrambleRate]);
		var holesTrig = Impulse.kr(p[\holesRate]);
		var diffuseTrig = Impulse.kr(2) * p[\diffuse];
		var combPhase = Phasor.kr(0, p[\combRate] * ControlDur.ir, 0, 1);
		var gate = p[\gate].dbamp * size * 0.5 * BinaryOpUGen('>', p[\gate], -95);
		// PV_MagFreeze holds the magnitudes and takes its phases from the input,
		// and silence has none to give: a frozen note would turn into a buzz at
		// the frame rate. So while held, the FFT hears the last moment of sound
		// looped, which has the right phases for what is being held
		var memory = FxLoop(sig.size, 0.5, 1 - held);
		var source = memory.buffers.collect { |b|
			var trig = Impulse.ar(40);
			GrainBuf.ar(1, trig, 0.12, b, 1,
				(memory.writePhase - (TRand.ar(0.13, 0.3, trig) * SampleRate.ir)).wrap(0, memory.frames) / memory.frames,
				2, 0, -1, 16)
		};
		var wet = sig.collect { |x, i|
			var heard = x + ((source[i] - x) * held);
			// stretch is a pitch shift ahead of the FFT: stretching the bins themselves
			// leaves their phases wrong, and the frames cancel (43 dB down at 0.5)
			var c = FFT(LocalBuf(size), Select.ar(stretched, [heard, PitchShift.ar(heard, 0.1, p[\warp], 0, 0.004)]), 0.25);
			c = PV_MagFreeze(c, held);
			// a bin moved n places needs n * pi/2 more phase each frame at this hop,
			// or the frames cancel: 66 dB down on a sine at a shift of 10
			c = PV_PhaseShift(PV_BinShift(c, 1, p[\shift], 1), (p[\shift] * 0.5pi).wrap(-pi, pi), 1);
			c = PV_MagSmear(c, p[\smear]);
			c = PV_BinScramble(c, p[\jumble], p[\scrambleWidth], scrambleTrig);
			c = PV_RandComb(c, p[\holes], holesTrig);
			c = PV_Diffuser(c, diffuseTrig);
			// no teeth is the whole spectrum: a comb all of whose teeth pass
			c = PV_RectComb(c, p[\teeth].max(1), combPhase,
				Select.kr(BinaryOpUGen('>', p[\teeth], 0), [1, p[\combWidth]]));
			c = PV_MagAbove(c, gate);
			c = PV_BrickWall(c, p[\brick]);
			IFFT(c) * 0.5
		};
		memory.write(sig);
		ctx[\dry] = DelayN.ar(sig, size * 2 / 44100 + 0.01, this.latencySignal);
		// for the display only: is it held, and where the comb's teeth are
		ctx[\extras] = [held, combPhase];
		^FxKit.width(wet, p[\width])
	}
}
