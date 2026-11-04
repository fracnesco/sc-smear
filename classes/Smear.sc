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
//   m.frozen = true;                    // hold it
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
	*windowSize { ^Point(860, 500) }

	*params {
		^[
			this.toggle(\frozen, false, "freeze", "hold this spectrum, for as long as it is on", \time),
			this.knob(\hold, 0, 30, \lin, 0, "Hz", "hold", "take a new spectrum this often and hold it in between: 0 is off", section: \time),
			this.knob(\smear, 0, 64, \lin, 0, "bins", "smear", "how many bins each side are averaged in", lag: 0, section: \time, dec: 0, step: 1),
			this.knob(\scramble, 0, 1, \lin, 0, "", "scramble", "how many bins are swapped", box: \pct, section: \time),
			this.knob(\scrambleWidth, 0.01, 1, \lin, 0.2, "", "reach", "how far apart they may be", box: \pct, section: \time),
			this.knob(\scrambleRate, 0.1, 30, \exp, 4, "Hz", "rate", "how often a new order is drawn", section: \time),
			this.toggle(\diffuse, false, "diffuse", "new phases, from time to time: transients wash away", \time),

			this.knob(\shift, -256, 256, \lin, 0, "bins", "shift", "add this many bins to every partial, about 23 Hz each: harmonics stop being harmonics",
				lag: 0, section: \pitch, dec: 0, step: 1),
			this.knob(\stretch, 0.25, 4, \exp, 1, "x", "stretch", "multiply every partial: a pitch shift, with its own 100 ms of delay", section: \pitch),
			this.knob(\holes, 0, 1, \lin, 0, "", "holes", "how many bins are taken away at random", box: \pct, section: \pitch),
			this.knob(\holesRate, 0.1, 30, \exp, 2, "Hz", "rate", "how often new ones are drawn", section: \pitch),
			this.knob(\teeth, 0, 48, \lin, 0, "", "comb", "how many teeth are taken out of the spectrum: 0 is none", lag: 0, section: \pitch, dec: 0, step: 1),
			this.knob(\combWidth, 0.02, 0.98, \lin, 0.5, "", "width", "how much of each tooth passes", box: \pct, section: \pitch),
			this.knob(\combRate, 0, 8, \lin, 0.25, "Hz", "sweep", "how quickly the teeth move", section: \pitch),

			this.knob(\gate, -96, 0, \lin, -96, "dB", "gate", "only what is over this comes through: the loudest partials", section: \output, dec: 0),
			this.knob(\brick, -1, 1, \lin, 0, "", "brick", "under 0 takes the top of the spectrum off, over 0 the bottom", section: \output),
			this.knob(\width, 0, 2, \lin, 1, "", "width", "how far apart the sides are", box: \pct, section: \output),
			this.mixParams(\output)
		]
	}

	*presetList {
		^[
			\default, (),
			'held breath', (frozen: true, smear: 2, mix: 0.8),
			'stepped ghost', (hold: 6, smear: 3),
			'melted', (smear: 24, diffuse: true),
			'glass choir', (hold: 1.5, smear: 1, stretch: 2, scramble: 0.05, mix: 0.7),
			'bin shuffle', (scramble: 0.6, scrambleWidth: 0.3, scrambleRate: 8),
			'sinking', (shift: -32, hold: 12, smear: 2),
			'swiss cheese', (holes: 0.7, holesRate: 5),
			'sweeping comb', (teeth: 12, combWidth: 0.4, combRate: 0.5),
			'top partials', (gate: -36, smear: 1, stretch: 1.5)
		]
	}

	// how far the wet is behind what came in, in seconds, given the server's
	// block and sample rate: what the dry is delayed by
	*latencySignal { ^(fftSize * SampleDur.ir) - ControlDur.ir }

	*process { |sig, p, ctx|
		var size = fftSize;
		var stretched = BinaryOpUGen('!=', p[\stretch], 1);
		var held = (p[\frozen] + (BinaryOpUGen('>', p[\hold], 0)
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
			var c = FFT(LocalBuf(size), Select.ar(stretched, [heard, PitchShift.ar(heard, 0.1, p[\stretch], 0, 0.004)]), 0.25);
			c = PV_MagFreeze(c, held);
			// a bin moved n places needs n * pi/2 more phase each frame at this hop,
			// or the frames cancel: 66 dB down on a sine at a shift of 10
			c = PV_PhaseShift(PV_BinShift(c, 1, p[\shift], 1), (p[\shift] * 0.5pi).wrap(-pi, pi), 1);
			c = PV_MagSmear(c, p[\smear]);
			c = PV_BinScramble(c, p[\scramble], p[\scrambleWidth], scrambleTrig);
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
		ctx[\extras] = [held];
		^FxKit.width(wet, p[\width])
	}
}
