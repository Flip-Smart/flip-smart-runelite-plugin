package com.flipsmart;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * While logged out the session RSN is cleared, and render-frame callers (GE overlays, the Active
 * Flips projection) keep asking for it. Warning on every call flooded client.log at ~300 lines/s.
 * The warning should fire once per stretch without an RSN, and again only after one resolved.
 */
public class RsnMissingWarningTest
{
    private static final String MESSAGE = "Unable to determine RSN - transactions will be recorded without RSN";

    private FlipSmartPlugin plugin;
    private PlayerSession session;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @Before
    public void setUp() throws Exception
    {
        plugin = new FlipSmartPlugin();
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        session = mock(PlayerSession.class);
        inject("client", client);
        inject("session", session);

        logger = (Logger) LoggerFactory.getLogger(FlipSmartPlugin.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @After
    public void tearDown()
    {
        logger.detachAppender(appender);
    }

    private void inject(String field, Object value) throws Exception
    {
        Field f = FlipSmartPlugin.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    private long warnings()
    {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN && MESSAGE.equals(e.getFormattedMessage()))
            .count();
    }

    @Test
    public void warnsOnceAcrossRepeatedLookupsWhileLoggedOut()
    {
        for (int i = 0; i < 300; i++)
        {
            assertFalse(plugin.getCurrentRsnSafe().isPresent());
        }

        assertEquals("one warning per stretch without an RSN, not one per frame", 1, warnings());
    }

    @Test
    public void warnsAgainAfterAnRsnResolvedAndWasLostAgain()
    {
        plugin.getCurrentRsnSafe();

        when(session.getRsn()).thenReturn("Zezima");
        assertEquals("Zezima", plugin.getCurrentRsnSafe().orElse(null));

        when(session.getRsn()).thenReturn(null);
        plugin.getCurrentRsnSafe();
        plugin.getCurrentRsnSafe();

        assertEquals("a fresh loss of the RSN is reported once more", 2, warnings());
    }
}
