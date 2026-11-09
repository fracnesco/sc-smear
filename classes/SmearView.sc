// =====================================================================
// SmearView - Smear's display: the spectrum coming out, and every
// operation drawn where it acts on it
//
// Along: frequency, 20 Hz to 20 kHz on a log ruler. Top to bottom:
//
//   partials  the first 48 partials of a 110 Hz note going in (blue) and
//             where Smear puts them (pink): moved by warp and shift, thrown
//             about by jumble, blurred into a band by smear, hollow where
//             holes, the comb or a wall take them away. A model of the
//             settings, not a measurement - jumble and holes are drawn anew
//             at their own rates, so they flicker as the sound does
//   comb      the line between the two: a notch per closed tooth, moving
//             with the server's own phase while it runs
//   spectrum  the output's spectrum, dB up, blue while it flows, with the
//             last few readings behind it; held, the picture taken as it
//             froze stays steady in pink over the faint live output. The
//             closed teeth, the brick wall's side and what the gate drops
//             are shaded where they act
//
// The caption says what is on, and what it costs: the FFT's latency.
//
//   click the spectrum          freeze, and let go
//   drag along it               shift (shift key: fine)
//   drag up and down            smear
//   the wheel                   jumble
//   double click                shift and smear back to 0
//   drag along the partials     warp; the wheel a semitone; double click 1x
//   drag the gate's line        the gate (its handle sits bottom right while off);
//                               the wheel a dB; double click off
//   drag a wall's grip          the brick wall, from the bottom or the top;
//                               double click takes it away
// =====================================================================

SmearView : FxParamView {
	// the layout as last measured
	var lx0 = 34, lx1 = 100, laneTop = 20, laneBot = 80, fieldTop = 90, fieldBot = 200;
	// what the replies bring: the last few spectra, the picture held
	var ghosts, lastGhost = 0, wasHeld = false, heldSince = 0, snapshot;
	// a drag going on
	var dragMode, dragX, dragY, dragFrom, dragAxis, dragShift = false, freezeBefore, dragMoved = false;

	*minHeight { ^240 }
	*wantsSpectrum { ^true }

	// the note whose partials the top lane follows, and how many of them
	*note { ^110 }
	*numPartials { ^48 }
	*ghostCount { ^4 }
	// PV_MagAbove on a sine-windowed frame: a sine passes once it is about this
	// far over the gate's setting (magnitude 0.32 A N against g N / 2)
	*gateOffset { ^3.9 }

	// a stateless hash in 0..1: the lane's stand-in for the server's random bins
	*hash { |a, b| ^(((a * 12.9898) + (b * 78.233)).sin * 43758.5453).abs.frac }

	// ------------------------------------------------------------ layout

	prLayout {
		lx0 = 34;
		lx1 = (bw - 8).max(lx0 + 10);
		laneTop = 22;
		laneBot = laneTop + (bh * 0.25).round.clip(56, 84);
		fieldTop = laneBot + 10;
		fieldBot = (bh - 16).max(fieldTop + 20);
	}

	prX { |f| ^lx0 + ((lx1 - lx0) * f.max(1).explin(20, 20000, 0, 1, \minmax)) }
	prFreqAt { |x| ^((x - lx0) / (lx1 - lx0)).clip(0, 1).linexp(0, 1, 20, 20000) }
	prY { |db| ^db.linlin(ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb, fieldBot, fieldTop, \minmax) }
	prDbAt { |y| ^y.linlin(fieldTop, fieldBot, ParaEQSpectrum.topDb, ParaEQSpectrum.floorDb, nil) }

	prServer { ^unit.tryPerform(\server) ? Server.default }
	prSampleRate { ^this.prServer.tryPerform(\sampleRate) ? 48000 }
	prNyquist { ^this.prSampleRate * 0.5 }
	prBinHz { ^this.prSampleRate / Smear.fftSize }
	// what the FFT costs, in ms: the dry waits as long
	prLatencyMs { ^(Smear.fftSize - (this.prServer.options.blockSize ? 64)) / this.prSampleRate * 1000 }

	// ------------------------------------------------------- what is known

	prLevels {
		var l;
		if (spectrum.isNil or: { spectrum.visible.not }) { ^nil };
		l = spectrum.levels;
		^if (l.notNil and: { l.any { |db| db > (ParaEQSpectrum.floorDb + 1) } }) { l }
	}

	prFreqs { ^spectrum.tryPerform(\freqs) ? ParaEQSpectrum.freqs }

	prLive { ^unit.isAlive and: { (unit.extras ? []).size >= 2 } }

	// held, as the server says while it runs (the stepped hold too), else as set
	prHeld { ^if (this.prLive) { unit.extras[0] > 0.5 } { unit.get(\freezeOn) == true } }

	// where the comb's teeth are: the server's phase while it runs, else the
	// model's, moving at the sweep
	prCombPhase {
		^if (this.prLive) { unit.extras[1] } { (Main.elapsedTime * unit.get(\combRate)).frac }
	}

	prBrickFreq {
		var b = unit.get(\brick);
		^case { b < 0 } { (1 + b) * this.prNyquist } { b > 0 } { b * this.prNyquist } { nil }
	}

	prClosed { |f, phase|
		var teeth = unit.get(\teeth);
		if (teeth < 1) { ^false };
		^((phase + (f * teeth / this.prNyquist)) % 1) > unit.get(\combWidth)
	}

	prCutAway { |f|
		var b = unit.get(\brick), nyq = this.prNyquist;
		^((b < 0) and: { f > ((1 + b) * nyq) }) or: { (b > 0) and: { f < (b * nyq) } }
	}

	// the note's partials, in and out, as the settings move them
	prPartials { |phase|
		var nyq = this.prNyquist, binHz = this.prBinHz, t = Main.elapsedTime;
		var frozen = unit.get(\freezeOn) == true;
		var warp = unit.get(\warp), shift = unit.get(\shift), jumble = unit.get(\jumble);
		var reach = unit.get(\scrambleWidth), holes = unit.get(\holes);
		var eS = (t * unit.get(\scrambleRate)).floor, eH = (t * unit.get(\holesRate)).floor;
		^(1..this.class.numPartials).collect { |h|
			var fin = h * this.class.note, fout, gone = false;
			// frozen, the magnitudes are held: warp (ahead of the freeze) waits
			fout = (if (frozen) { fin } { fin * warp }) + (shift * binHz);
			if ((jumble > 0) and: { this.class.hash(h, eS) < jumble }) {
				fout = fout + ((this.class.hash(h + 100, eS) * 2 - 1) * reach * nyq);
			};
			if ((holes > 0) and: { this.class.hash(h + 300, eH) < holes }) { gone = true };
			// moved under 0 or over the top, it is gone; under 20 Hz or over 20 kHz, off the picture
			if ((fout < 0) or: { fout > nyq }) { gone = true };
			if (this.prClosed(fout, phase) or: { this.prCutAway(fout) }) { gone = true };
			(fin: fin, fout: fout.clip(20, nyq), gone: gone, off: (fout < 20) or: { fout > 20000 })
		}
	}

	// the gate's handle: on its line, or parked bottom right while off
	prGateOn { ^unit.get(\gate) > -95 }
	prGateY { ^this.prY(unit.get(\gate) + this.class.gateOffset) }
	prGateRect {
		^if (this.prGateOn) { Rect(lx1 - 14, this.prGateY - 5, 12, 10) } { Rect(lx1 - 14, fieldBot - 11, 12, 10) }
	}

	// a wall's x: where it cuts, or parked at its edge while that side cuts nothing
	prWallActive { |side| var b = unit.get(\brick); ^if (side == \low) { b > 0 } { b < 0 } }
	prWallX { |side|
		^if (this.prWallActive(side)) { this.prX(this.prBrickFreq).clip(lx0, lx1) } { if (side == \low) { lx0 } { lx1 } }
	}
	prGripRect { |side| var x = this.prWallX(side); ^Rect(x - 5, fieldBot - 36, 10, 18) }

	// ------------------------------------------------------------ replies

	levelsArrived {
		var now = Main.elapsedTime, levels = this.prLevels, held;
		if (unit.isNil) { ^this };
		held = this.prHeld;
		if (levels.notNil and: { (now - lastGhost) >= 0.2 }) {
			ghosts = ([levels.copy] ++ (ghosts ? [])).keep(this.class.ghostCount);
			lastGhost = now;
		};
		// the picture held: what came out in the first moments of the freeze
		// (the analyser runs a frame behind), then steady until it lets go
		if (held) {
			if (wasHeld.not) { heldSince = now };
			if (levels.notNil and: { (now - heldSince) < 0.35 }) { snapshot = levels.copy };
		} {
			snapshot = nil
		};
		wasHeld = held;
	}

	// ------------------------------------------------------------- draw

	prDraw {
		var id = unit.class.identityColour ? FxPalette.accent, held = this.prHeld, phase = this.prCombPhase;
		this.prLayout;
		Pen.smoothing = true;
		this.prDrawGrid;
		this.prDrawBrick(id, true);
		this.prDrawComb(id, phase);
		this.prDrawSpectrum(id, held);
		this.prDrawGate(id);
		this.prDrawBrick(id, false);
		this.prDrawPartials(id, phase);
		this.prDrawCaption(id, held);
	}

	prDrawGrid {
		var small = FxPalette.smallFont;
		[50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000].do { |f|
			var x = this.prX(f), major = [100, 1000, 10000].includes(f);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (major) { 0.75 } { 0.4 });
			Pen.width = 1;
			Pen.line(x @ (laneTop + 14), x @ fieldBot);
			Pen.stroke;
			Pen.stringCenteredIn(if (f >= 1000) { (f div: 1000).asString ++ "k" } { f.asString },
				Rect(x - 16, fieldBot + 2, 32, 12), small, FxPalette.dim);
		};
		[0, -24, -48, -72].do { |db|
			var y = this.prY(db);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.7 } { 0.4 });
			Pen.line(lx0 @ y, lx1 @ y);
			Pen.stroke;
			Pen.stringRightJustIn(db.asString, Rect(0, y - 6, lx0 - 6, 12), small, FxPalette.dim);
		};
		Pen.stringRightJustIn("dB", Rect(0, fieldTop - 2, lx0 - 6, 12), small, FxPalette.dim);
	}

	// shade: what the wall takes, through lane and field; else the walls and grips
	prDrawBrick { |id, shade|
		var fc = this.prBrickFreq, x0, small = FxPalette.smallFont;
		if (shade) {
			fc !? {
				x0 = this.prX(fc).clip(lx0, lx1);
				Pen.fillColor = Color.black.alpha_(0.45);
				if (unit.get(\brick) < 0) {
					Pen.fillRect(Rect(x0, laneTop + 14, lx1 - x0, fieldBot - laneTop - 14));
				} {
					Pen.fillRect(Rect(lx0, laneTop + 14, x0 - lx0, fieldBot - laneTop - 14));
				};
			};
			^this
		};
		[\low, \high].do { |side|
			var active = this.prWallActive(side), x = this.prWallX(side), grip = this.prGripRect(side);
			var dragged = dragMode == side, colour = if (dragged) { FxPalette.accent } { id };
			if (active) {
				Pen.strokeColor = colour;
				Pen.width = 1.5;
				Pen.line(x @ (laneTop + 14), x @ fieldBot);
				Pen.stroke;
				if (side == \high) {
					Pen.stringRightJustIn("cut over " ++ FxPalette.format(fc, "Hz"), Rect(x - 128, fieldTop + 2, 120, 12), small, colour);
				} {
					Pen.stringLeftJustIn("cut under " ++ FxPalette.format(fc, "Hz"), Rect(x + 8, fieldTop + 2, 120, 12), small, colour);
				};
			};
			Pen.fillColor = if (active or: { dragged }) { colour } { FxPalette.fade(FxPalette.waveDim, 0.9) };
			Pen.addRoundedRect(grip, 3, 3);
			Pen.fill;
			Pen.strokeColor = FxPalette.fade(FxPalette.bg, 0.8);
			Pen.width = 1;
			[-2, 2].do { |dx| Pen.line((x + dx) @ (grip.top + 5), (x + dx) @ (grip.bottom - 5)) };
			Pen.stroke;
		};
	}

	// the closed teeth shaded in the field, and the comb as the line over it
	prDrawComb { |id, phase|
		var teeth = unit.get(\teeth), open = unit.get(\combWidth), nyq = this.prNyquist;
		var yOpen = laneBot + 2, yShut = laneBot + 7, segs = [], period;
		if (teeth < 1) {
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.6);
			Pen.width = 1;
			Pen.line(lx0 @ yOpen, lx1 @ yOpen);
			Pen.stroke;
			^this
		};
		// a tooth is open where (phase + f / period) mod 1 is under `open`
		period = nyq / teeth;
		(teeth.asInteger + 2).do { |k|
			var a = ((k - 1) + open - phase) * period, b = (k - phase) * period;
			a = a.max(20);
			b = b.min(20000);
			if (b > a) { segs = segs.add([a, b]) };
		};
		Pen.fillColor = Color.black.alpha_(0.32);
		segs.do { |s| Pen.fillRect(Rect(this.prX(s[0]), fieldTop, this.prX(s[1]) - this.prX(s[0]), fieldBot - fieldTop)) };
		Pen.strokeColor = FxPalette.fade(id, 0.9);
		Pen.width = 1.25;
		Pen.moveTo(lx0 @ yOpen);
		segs.do { |s|
			var a = this.prX(s[0]), b = this.prX(s[1]);
			Pen.lineTo(a @ yOpen);
			Pen.lineTo(a @ yShut);
			Pen.lineTo(b @ yShut);
			Pen.lineTo(b @ yOpen);
		};
		Pen.lineTo(lx1 @ yOpen);
		Pen.stroke;
	}

	prPath { |levels, freqs, dx = 0, dy = 0|
		levels.do { |db, i|
			var p = (this.prX(freqs[i]) + dx) @ (this.prY(db) + dy);
			if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
		};
	}

	prFill { |levels, freqs|
		Pen.moveTo(this.prX(freqs[0]) @ fieldBot);
		levels.do { |db, i| Pen.lineTo(this.prX(freqs[i]) @ this.prY(db)) };
		Pen.lineTo(this.prX(freqs.last) @ fieldBot);
	}

	prDrawSpectrum { |id, held|
		var levels = this.prLevels, freqs = this.prFreqs, picture;
		Pen.stringAtPoint("spectrum out", (lx0 + 6) @ (fieldTop + 2), FxPalette.smallFont, FxPalette.dim);
		if (levels.isNil) {
			Pen.stringCenteredIn(
				if (unit.isAlive) { "nothing is coming out" } { "the spectrum coming out shows here while it runs" },
				Rect(lx0, fieldTop, lx1 - lx0, fieldBot - fieldTop), FxPalette.smallFont, FxPalette.fade(FxPalette.dim, 0.8));
			^this
		};
		// the last few readings, stacked back and up: the feel of time, cheaply
		(ghosts ? []).do { |g, k|
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.16 - (k * 0.03));
			Pen.width = 1;
			this.prPath(g, freqs, (k + 1) * 3, (k + 1) * -4);
			Pen.stroke;
		};
		picture = if (held) { snapshot ? levels };
		if (picture.notNil) {
			// held: the live output faint, the picture steady in the identity over it
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.14);
			this.prFill(levels, freqs);
			Pen.fill;
			Pen.fillColor = FxPalette.fade(id, 0.22);
			this.prFill(picture, freqs);
			Pen.fill;
			Pen.strokeColor = id;
			Pen.width = 1.75;
			this.prPath(picture, freqs);
			Pen.stroke;
		} {
			Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.28);
			this.prFill(levels, freqs);
			Pen.fill;
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.9);
			Pen.width = 1.25;
			this.prPath(levels, freqs);
			Pen.stroke;
		};
	}

	prDrawGate { |id|
		var r = this.prGateRect, y, colour = if (dragMode == \gate) { FxPalette.accent } { id };
		if (this.prGateOn) {
			y = this.prGateY;
			Pen.fillColor = Color.black.alpha_(0.22);
			Pen.fillRect(Rect(lx0, y, lx1 - lx0, fieldBot - y));
			Pen.strokeColor = colour;
			Pen.width = 1.5;
			Pen.line(lx0 @ y, lx1 @ y);
			Pen.stroke;
			Pen.fillColor = colour;
			Pen.addRoundedRect(r, 2, 2);
			Pen.fill;
			Pen.stringRightJustIn("gate " ++ FxPalette.format(unit.get(\gate), "dB", nil, 0),
				Rect(lx0, y - 15, lx1 - lx0 - 18, 12), FxPalette.smallFont, colour);
		} {
			Pen.fillColor = if (dragMode == \gate) { colour } { FxPalette.fade(FxPalette.waveDim, 0.9) };
			Pen.addRoundedRect(r, 2, 2);
			Pen.fill;
			Pen.stringRightJustIn("gate off", Rect(lx0, fieldBot - 13, lx1 - lx0 - 18, 12), FxPalette.smallFont, FxPalette.dim);
		};
	}

	prDrawPartials { |id, phase|
		var parts = this.prPartials(phase), small = FxPalette.smallFont;
		var inTop = laneTop + 18, inBot = inTop + 9, outBot = laneBot - 5, outTop = outBot - 9;
		var half = unit.get(\smear) * this.prBinHz;
		Pen.stringAtPoint("partials of a 110 Hz note", (lx0 + 6) @ (laneTop + 1), small, FxPalette.dim);
		Pen.stringRightJustIn("in", Rect(0, inTop - 2, lx0 - 6, 12), small, FxPalette.dim);
		Pen.stringRightJustIn("out", Rect(0, outTop - 2, lx0 - 6, 12), small, FxPalette.dim);
		// smear: each partial melted into a band as wide as the bins it is averaged over
		if (half > 0) {
			Pen.fillColor = FxPalette.fade(id, 0.18);
			parts.do { |p|
				var a = this.prX((p[\fout] - half).max(20)), b = this.prX(p[\fout] + half);
				if (p[\gone].not and: { p[\off].not }) { Pen.fillRect(Rect(a, outTop - 2, (b - a).max(2), 13)) };
			};
		};
		parts.do { |p|
			var xi = this.prX(p[\fin]), xo = this.prX(p[\fout]);
			Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.7);
			Pen.width = 1.5;
			Pen.line(xi @ inTop, xi @ inBot);
			Pen.stroke;
			// off the picture: only where it came from
			if (p[\off].not) {
				Pen.strokeColor = FxPalette.fade(id, if (p[\gone]) { 0.14 } { 0.4 });
				Pen.width = 1;
				Pen.line(xi @ (inBot + 2), xo @ (outTop - 2));
				Pen.stroke;
				if (p[\gone]) {
					Pen.strokeColor = FxPalette.fade(id, 0.4);
					Pen.addRect(Rect(xo - 1.5, outTop, 3, 9));
					Pen.stroke;
				} {
					Pen.strokeColor = if (dragMode == \lane) { FxPalette.accent } { id };
					Pen.width = 2;
					Pen.line(xo @ outTop, xo @ outBot);
					Pen.stroke;
				};
			};
		};
		if ((unit.get(\freezeOn) == true) and: { unit.get(\warp) != 1 }) {
			Pen.stringRightJustIn("frozen: warp waits until it lets go", Rect(lx0, laneTop + 1, lx1 - lx0 - 4, 12), small, FxPalette.dim);
		};
	}

	// what is on, left; what it costs, right
	prDrawCaption { |id, held|
		var word, wcol, x, parts = [], right, room, line = "", f = FxPalette.smallFont;
		var hold = unit.get(\hold), gate = unit.get(\gate);
		word = case
		{ unit.get(\freezeOn) == true } { "frozen" }
		{ hold > 0 } { "stepped, % a second".format(FxPalette.fixed(hold, if (hold < 10) { 1 } { 0 })) }
		{ true } { "flowing" };
		wcol = if ((unit.get(\freezeOn) == true) or: { hold > 0 }) { id } { FxPalette.dim };
		if (unit.get(\smear) > 0) {
			parts = parts.add("smear % %".format(unit.get(\smear).round.asInteger, if (unit.get(\smear).round == 1) { "bin" } { "bins" }))
		};
		if ((unit.get(\warp) != 1) and: { unit.get(\freezeOn) != true }) {
			parts = parts.add("warp % (% st)".format(FxPalette.format(unit.get(\warp), "x"),
				FxPalette.fixed(12 * log2(unit.get(\warp)), 1)))
		};
		if (unit.get(\shift) != 0) {
			parts = parts.add("shift % bins (% Hz)".format(unit.get(\shift).round.asInteger,
				(unit.get(\shift) * this.prBinHz).round.asInteger))
		};
		if (unit.get(\jumble) > 0) { parts = parts.add("jumble " ++ FxPalette.format(unit.get(\jumble), "", \pct)) };
		if (unit.get(\diffuse) == true) { parts = parts.add("diffuse") };
		if (unit.get(\holes) > 0) { parts = parts.add("holes " ++ FxPalette.format(unit.get(\holes), "", \pct)) };
		if (unit.get(\teeth) >= 1) { parts = parts.add("comb % teeth".format(unit.get(\teeth).round.asInteger)) };
		if (gate > -95) { parts = parts.add("gate " ++ FxPalette.format(gate, "dB", nil, 0)) };
		this.prBrickFreq !? { |fc|
			parts = parts.add("cut % %".format(if (unit.get(\brick) < 0) { "over" } { "under" }, FxPalette.format(fc, "Hz")))
		};
		right = "fft % · % ms late".format(Smear.fftSize, this.prLatencyMs.round.asInteger);
		Pen.stringAtPoint(word, 8 @ 3, FxPalette.font, wcol);
		x = 8 + word.bounds(FxPalette.font).width;
		// what fits before the cost on the right
		room = bw - 16 - right.bounds(f).width - x - 16;
		parts.do { |p|
			var next = line ++ "   ·   " ++ p;
			if (next.bounds(f).width <= room) { line = next };
		};
		if (line.notEmpty) { Pen.stringAtPoint(line, x @ 5, f, FxPalette.dim) };
		Pen.stringRightJustIn(right, Rect(0, 5, bw - 8, 12), f, FxPalette.fade(FxPalette.dim, 0.85));
	}

	// ------------------------------------------------------------ mouse

	prRegion { |x, y|
		this.prLayout;
		if (y < laneTop) { ^\caption };
		if (this.prGateRect.insetBy(-4, -4).contains(x @ y)) { ^\gate };
		if (this.prGateOn and: { (y - this.prGateY).abs <= 5 } and: { x >= lx0 } and: { x <= lx1 }) { ^\gate };
		[\low, \high].do { |side|
			if (this.prGripRect(side).insetBy(-4, -4).contains(x @ y)) { ^side };
			if (this.prWallActive(side) and: { (x - this.prWallX(side)).abs <= 5 } and: { y >= laneTop }) { ^side };
		};
		if (y < (laneBot + 5)) { ^\lane };
		^\field
	}

	prMouseDown { |x, y, mod, count|
		var region = this.prRegion(x, y);
		dragShift = mod.isShift;
		dragX = x;
		dragY = y;
		dragAxis = nil;
		dragMoved = false;
		dragMode = nil;
		if (count == 2) {
			switch (region,
				\gate, { this.prDefault(\gate) },
				\low, { unit.set(\brick, 0) },
				\high, { unit.set(\brick, 0) },
				\lane, { this.prDefault(\warp) },
				\field, {
					// the first click of the two froze it: undo that
					freezeBefore !? { |b| unit.set(\freezeOn, b) };
					this.prDefault(\shift);
					this.prDefault(\smear);
				}
			);
			^this
		};
		dragMode = region;
		dragFrom = switch (region,
			\gate, { unit.get(\gate) },
			\low, { unit.get(\brick) },
			\high, { unit.get(\brick) },
			\lane, { unit.get(\warp) },
			{ nil }
		);
		if (region == \field) { freezeBefore = unit.get(\freezeOn) == true };
		this.prSayHint(x, y);
		this.refresh;
	}

	prMouseMove { |x, y, mod|
		var k, dx, dy, nyq, f;
		if (dragMode.isNil or: { dragMode == \caption }) { ^this };
		this.prLayout;
		// shift pressed or let go mid-drag: start again from here
		if (mod.isShift != dragShift) {
			dragShift = mod.isShift;
			dragX = x;
			dragY = y;
			dragFrom = switch (dragMode,
				\gate, { unit.get(\gate) }, \low, { unit.get(\brick) }, \high, { unit.get(\brick) },
				\lane, { unit.get(\warp) }, \field, { dragAxis !? { |a| unit.get(a) } }, { nil });
		};
		k = if (dragShift) { 0.1 } { 1 };
		dx = x - dragX;
		dy = dragY - y;
		if ((dx.abs > 2) or: { dy.abs > 2 }) { dragMoved = true };
		nyq = this.prNyquist;
		switch (dragMode,
			\gate, {
				unit.set(\gate, if (dragShift) { dragFrom + (dy * 0.1 * 84 / (fieldBot - fieldTop)) } {
					this.prDbAt(y) - this.class.gateOffset
				}.clip(-96, 0))
			},
			\low, {
				f = this.prFreqAt(x);
				unit.set(\brick, if (dragShift) { dragFrom + (dx / (lx1 - lx0) * 0.1) } {
					if (x <= (lx0 + 2)) { 0 } { f / nyq }
				}.clip(0, 1))
			},
			\high, {
				f = this.prFreqAt(x);
				unit.set(\brick, if (dragShift) { dragFrom + (dx / (lx1 - lx0) * 0.1) } {
					if (x >= (lx1 - 2)) { 0 } { (f / nyq) - 1 }
				}.clip(-1, 0))
			},
			\lane, { unit.set(\warp, (dragFrom * (16 ** (dx / 200 * k))).clip(0.25, 4)) },
			\field, {
				// one axis a drag, chosen by the first few pixels; the value then
				// follows the pointer from where it was pressed
				if (dragAxis.isNil and: { (dx.abs > 4) or: { dy.abs > 4 } }) {
					dragAxis = if (dx.abs > dy.abs) { \shift } { \smear };
					dragFrom = unit.get(dragAxis);
				};
				switch (dragAxis,
					\shift, { unit.set(\shift, (dragFrom + (dx * 512 / 200 * k)).round.clip(-256, 256)) },
					\smear, { unit.set(\smear, (dragFrom + (dy * 64 / 200 * k)).round.clip(0, 64)) }
				);
			}
		);
		this.prSayHint(x, y);
	}

	prMouseUp {
		// a click in the spectrum that went nowhere: freeze, or let go
		if ((dragMode == \field) and: { dragMoved.not } and: { dragAxis.isNil }) {
			unit.set(\freezeOn, (unit.get(\freezeOn) == true).not);
		};
		dragMode = nil;
		dragAxis = nil;
		this.refresh;
	}

	prWheel { |x, y, delta|
		var d = delta.sign;
		if (d == 0) { ^this };
		switch (this.prRegion(x, y),
			\gate, { unit.set(\gate, (unit.get(\gate).max(-90) + d).clip(-96, 0)) },
			\low, { unit.set(\brick, (unit.get(\brick) + (d * 0.01)).clip(0, 1)) },
			\high, { unit.set(\brick, (unit.get(\brick) - (d * 0.01)).clip(-1, 0)) },
			\lane, { unit.set(\warp, (unit.get(\warp) * (2 ** (d / 12))).clip(0.25, 4)) },
			\field, { unit.set(\jumble, (unit.get(\jumble) + (d * 0.02)).clip(0, 1)) }
		);
		this.prSayHint(x, y);
	}

	prDefault { |key| unit.set(key, unit.class.param(key)[\default]) }

	// the hint bar follows a drag: the panel's hint for this view, asked again
	prSayHint { |x, y| view.mouseOverAction.value(view, x, y) }

	// ------------------------------------------------------------ hint

	hint { |x, y|
		var fmt = { |k| var e = unit.class.param(k); FxPalette.format(unit.get(k), e[\spec].units, e[\box], e[\dec], e[\bipolar], e[\floorText], e[\spec]) };
		if (unit.isNil) { ^"" };
		^switch (this.prRegion(x ? 0, y ? 0),
			\caption, {
				"what is on, and what it costs: the FFT puts the wet % ms late, and the dry waits as long, whatever the settings"
					.format(this.prLatencyMs.round.asInteger)
			},
			\gate, {
				"the gate (%): only partials over its line come through - a sine about 4 dB over the setting - drag it up and down (shift: fine), the wheel a dB, double click: off"
					.format(fmt.(\gate))
			},
			\low, { "the brick wall from the bottom (%): drag it right to take the lows away, double click: no wall".format(this.prBrickWords) },
			\high, { "the brick wall from the top (%): drag it left to take the highs away, double click: no wall".format(this.prBrickWords) },
			\lane, {
				"the partials of a 110 Hz note going in (blue) and coming out (pink): moved by warp (%) and shift, blurred by smear, hollow where they are taken away - drag along for the warp (shift: fine), the wheel a semitone, double click: 1x"
					.format(fmt.(\warp))
			},
			{
				"the spectrum coming out%: click to freeze or let go, drag along for the shift (%), up and down for the smear (%), the wheel for jumble (%) - double click: shift and smear to 0"
					.format(if (this.prHeld) { ", the held picture in pink" } { "" }, fmt.(\shift), fmt.(\smear), fmt.(\jumble))
			}
		)
	}

	prBrickWords {
		^this.prBrickFreq !? { |fc|
			"cutting % %".format(if (unit.get(\brick) < 0) { "over" } { "under" }, FxPalette.format(fc, "Hz"))
		} ? "off"
	}
}
