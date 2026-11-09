// Smear - a walkthrough, one block at a time
//
// Evaluate each ( ... ) block in turn with the cursor inside it. This is
// an editor document, not a file to .load.

// ---- 1. a sound, and Smear driven from its Ndef
(
s.boot;
)

(
Ndef(\voice, { Smear.ar(Saw.ar([110, 111]) * LFPulse.kr(2).lag(0.01) * 0.2, \smear) }).play;
~m = Smear(Ndef(\voice), \smear);
~m.gui;
)

// ---- 2. settings: each is a property, each moves the window
(
~m.freezeOn = true;                  // hold it
~m.freezeOn = false;
~m.preset = 'glass choir';
~m.smear = 12; ~m.shift = 16;
)

// ---- 3. every preset; evaluate one at a time
~m.preset = \default;
~m.preset = 'held breath';
~m.preset = 'stepped ghost';
~m.preset = \melted;
~m.preset = 'glass choir';
~m.preset = 'bin shuffle';
~m.preset = \sinking;
~m.preset = 'swiss cheese';
~m.preset = 'sweeping comb';
~m.preset = 'top partials';

// ---- 4. settings kept, and carried
(
~m.saveAsPreset("demo");
~m.copySettings;
Smear.presetNames.postln;
Smear.removePreset("demo");
)

// ---- 5. the same effect with a node of its own, on the main out
(
~own = Smear.onBus(0, 2);
~own.pasteSettings;
~own.enabled = true;
~own.gui;
)

(
~own.free;
)

// ---- 6. tidy up
(
~m.free;
Ndef(\voice).clear;
)
