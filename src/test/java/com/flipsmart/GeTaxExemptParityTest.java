package com.flipsmart;

import com.flipsmart.util.GeTax;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Parity guard for the GE tax-exempt item list (epic tracking-accuracy work).
 *
 * The OSRS GE tax-exempt set is duplicated across two repos: the backend's
 * {@code app.constants.GE_TAX_EXEMPT_ITEM_IDS} is the CANONICAL source of
 * truth, and {@link GeTax#EXEMPT_ITEM_IDS} mirrors it so the GE-slot hover
 * overlay can compute true post-tax profit without a network round-trip.
 *
 * This test pins the exact expected exempt-id set as a literal. The same
 * literal set is pinned in the backend repo's
 * {@code tests/test_ge_tax_exempt_parity.py}. Because both repos assert their
 * own list equals this identical pinned contract, the two lists cannot drift
 * apart while both test suites are green.
 *
 * When Jagex changes GE exemptions, update all four places in lockstep:
 * backend constants, the backend parity test, {@link GeTax}, and this test.
 */
public class GeTaxExemptParityTest
{
	/**
	 * Canonical cross-repo contract. Keep byte-identical to the backend's
	 * EXPECTED_GE_TAX_EXEMPT_ITEM_IDS in test_ge_tax_exempt_parity.py.
	 */
	private static final Set<Integer> EXPECTED_EXEMPT_ITEM_IDS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			233,    // Pestle and mortar
			315,    // Shrimps
			329,    // Salmon
			347,    // Herring
			351,    // Pike
			355,    // Mackerel
			361,    // Tuna
			365,    // Bass
			379,    // Lobster
			558,    // Mind rune
			806,    // Bronze dart
			807,    // Iron dart
			808,    // Steel dart
			882,    // Bronze arrow
			884,    // Iron arrow
			886,    // Steel arrow
			952,    // Spade
			1733,   // Needle
			1735,   // Shears
			1755,   // Chisel
			1785,   // Glassblowing pipe
			1891,   // Cake
			2140,   // Cooked chicken
			2142,   // Cooked meat
			2309,   // Bread
			2327,   // Meat pie
			2347,   // Hammer
			2552,   // Ring of dueling(8)
			3008,   // Energy potion(4)
			3853,   // Games necklace(8)
			5325,   // Gardening trowel
			5329,   // Secateurs
			5331,   // Watering can
			5341,   // Rake
			5343,   // Seed dibber
			8007,   // Varrock teleport (tablet)
			8008,   // Lumbridge teleport (tablet)
			8009,   // Falador teleport (tablet)
			8010,   // Camelot teleport (tablet)
			8011,   // Ardougne teleport (tablet)
			8013,   // Teleport to house (tablet)
			8794,   // Saw
			13190,  // Old school bond
			28790,  // Kourend castle teleport (tablet)
			28824   // Civitas illa fortis teleport
		)));

	@Test
	public void pluginExemptListMatchesPinnedContract()
	{
		Set<Integer> missing = new TreeSet<>(EXPECTED_EXEMPT_ITEM_IDS);
		missing.removeAll(GeTax.EXEMPT_ITEM_IDS);

		Set<Integer> unexpected = new TreeSet<>(GeTax.EXEMPT_ITEM_IDS);
		unexpected.removeAll(EXPECTED_EXEMPT_ITEM_IDS);

		assertEquals(
			"GE tax-exempt list drifted from the pinned cross-repo contract. "
				+ "Missing from plugin: " + missing + "; unexpected in plugin: "
				+ unexpected + ". Update GeTax.java, this test, and BOTH backend "
				+ "files in lockstep.",
			EXPECTED_EXEMPT_ITEM_IDS, GeTax.EXEMPT_ITEM_IDS);
	}

	@Test
	public void mindRuneExemptNatureRuneNot()
	{
		final int mindRuneId = 558;
		final int natureRuneId = 561;

		assertTrue("Mind rune (558) must be tax-exempt",
			GeTax.EXEMPT_ITEM_IDS.contains(mindRuneId));
		assertFalse("Nature rune (561) is NOT tax-exempt",
			GeTax.EXEMPT_ITEM_IDS.contains(natureRuneId));
		assertTrue(EXPECTED_EXEMPT_ITEM_IDS.contains(mindRuneId));
		assertFalse(EXPECTED_EXEMPT_ITEM_IDS.contains(natureRuneId));
	}
}
