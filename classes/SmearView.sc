// =====================================================================
// SmearView - Smear's display: the spectrum coming out, and what is in its way
//
// The output's spectrum, with the teeth of the comb, the brick wall and the
// stretch drawn over it from the settings. Blue is flowing; it turns orange
// while the spectrum is held.
//
//   click                  freeze, on and off
//   drag up and down       the smear
//   the wheel              the stretch
// =====================================================================

SmearView : FxParamView {
	var dragY, dragFrom;

	*wantsSpectrum { ^true }

	prDraw {
		var held = ((unit.extras ? [])[0] ? unit.frozen.binaryValue) > 0.5;
		var teeth = unit.teeth, brick = unit.brick;
		Pen.fillColor = FxPalette.bg;
		Pen.fillRect(Rect(0, 0, bw, bh));
		this.prDrawSpectrum(if (held) { FxPalette.accent.copy.alpha_(0.55) } { FxPalette.wave.copy.alpha_(0.4) });

		// the comb's teeth run evenly in bins, so they crowd to the right here
		if (teeth > 0) {
			Pen.fillColor = Color.black.alpha_(0.35);
			teeth.asInteger.do { |i|
				var binWidth = 24000 / teeth;
				var a = FxParamView.freqToX((i + unit.combWidth) * binWidth + 1, bw);
				var b = FxParamView.freqToX((i + 1) * binWidth, bw);
				Pen.fillRect(Rect(a, 0, (b - a).max(1), bh));
			};
		};
		if (brick != 0) {
			Pen.fillColor = Color.black.alpha_(0.5);
			if (brick < 0) {
				var x = FxParamView.freqToX(24000 * (1 + brick), bw);
				Pen.fillRect(Rect(x, 0, bw - x, bh));
			} {
				var x = FxParamView.freqToX(24000 * brick, bw);
				Pen.fillRect(Rect(0, 0, x, bh));
			};
		};
		if (unit.smear > 0) {
			Pen.fillColor = FxPalette.text.copy.alpha_(0.5);
			Pen.fillRect(Rect(bw * 0.5 - (unit.smear * 2), bh - 4, unit.smear * 4, 2));
		};
		this.prCaption(if (held) { "frozen" } { "flowing" } ++ "   smear " ++ unit.smear.asInteger
			++ "   stretch " ++ unit.stretch.round(0.01) ++ "   shift " ++ unit.shift.round(1));
	}

	prMouseDown { |x, y, mod, count|
		dragY = y;
		dragFrom = unit.smear;
	}

	prMouseMove { |x, y, mod|
		if (dragY.notNil and: { (y - dragY).abs > 2 }) {
			unit.smear = dragFrom + (dragY - y);
			dragFrom = unit.smear;
			dragY = y;
			^this
		};
	}

	prMouseUp { }

	prWheel { |x, y, delta| unit.stretch = unit.stretch * (1.01 ** delta.sign) }

	hint { ^"what comes out, with the comb and the brick wall over it - drag up and down for the smear, the wheel for the stretch; freeze is below" }
}
