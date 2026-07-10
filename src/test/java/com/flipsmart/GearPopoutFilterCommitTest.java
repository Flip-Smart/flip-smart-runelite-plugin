package com.flipsmart;

import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The gear pop-out filter spinners must capture a value the user TYPED even when
 * they dismiss the pop-out without pressing Enter (they click outside, or hit the
 * Update button). {@link FlipFinderPanel#commitSpinner} force-commits the editor
 * text to the model, reverting to the last valid value if the text is unparseable.
 */
public class GearPopoutFilterCommitTest
{
	private static JSpinner spinner(int value)
	{
		return new JSpinner(new SpinnerNumberModel(value, 0, Integer.MAX_VALUE, 100));
	}

	private static void type(JSpinner s, String text)
	{
		((JSpinner.DefaultEditor) s.getEditor()).getTextField().setText(text);
	}

	@Test
	public void commitsTypedValueWithoutEnter()
	{
		JSpinner s = spinner(1000);
		type(s, "5000");

		assertEquals(5000, FlipFinderPanel.commitSpinner(s));
		assertEquals(5000, s.getValue());
	}

	@Test
	public void revertsInvalidTypedTextToLastValue()
	{
		JSpinner s = spinner(1000);
		type(s, "not a number");

		assertEquals(1000, FlipFinderPanel.commitSpinner(s));
		assertEquals(1000, s.getValue());
	}

	@Test
	public void revertsEmptyTypedTextToLastValue()
	{
		JSpinner s = spinner(2000);
		type(s, "");

		assertEquals(2000, FlipFinderPanel.commitSpinner(s));
		assertEquals(2000, s.getValue());
	}
}
