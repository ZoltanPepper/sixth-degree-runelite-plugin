package com.sixthdegree;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SixthDegreeCompetitionTrackerTest
{
    @Test
    public void parsesRiftsAndPreservesBossCounts()
    {
        assertEquals(1627L, SixthDegreeCompetitionTracker.parseBossCount(
            "Amount of rifts you have closed: <col=ff0000>1,627</col>.").count);
        assertEquals("GOTR", SixthDegreeCompetitionTracker.parseBossCount(
            "Amount of rifts you have closed: 1.").boss);
        assertEquals(42L, SixthDegreeCompetitionTracker.parseBossCount("Your Vorkath kill count is: 42.").count);
        assertNull(SixthDegreeCompetitionTracker.parseBossCount("Amount of rifts you have closed: nope."));
        assertNull(SixthDegreeCompetitionTracker.parseBossCount("Amount of rifts you have closed: 9999999999999999999999."));
        assertNull(SixthDegreeCompetitionTracker.parseBossCount("Someone says Amount of rifts you have closed: 99."));
        for (String metric : new String[]{"GOTR", "Guardians of the Rift", "Rifts closed"})
        {
            assertTrue(SixthDegreeCompetitionTracker.metricMatches(metric, "GOTR"));
            assertEquals("1 Rift", SixthDegreePanel.formatScore(1, "BOTW", metric));
            assertEquals("2 Rifts", SixthDegreePanel.formatScore(2, "BOTW", metric));
        }
        assertFalse(SixthDegreeCompetitionTracker.metricMatches("Grotesque Guardians", "GOTR"));
        assertEquals("2 KC", SixthDegreePanel.formatScore(2, "BOTW", "Vorkath"));
        assertEquals("+2 XP", SixthDegreePanel.formatScore(2, "SOTW", "Runecraft"));
    }

    private static void set(Object object, String name, Object value) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Test
    public void riftsUseExistingDeltaTelemetryAndIgnoreDuplicates() throws Exception
    {
        SixthDegreeApiClient api = mock(SixthDegreeApiClient.class);
        when(api.postCompetitionProgress(anyString(), anyString(), anyInt(), anyLong(), anyLong(), anyLong(), anyString()))
            .thenReturn(new CompletableFuture<>());
        SixthDegreeCompetitionTracker tracker = new SixthDegreeCompetitionTracker(mock(Client.class), null, api);
        set(tracker, "active", true);
        set(tracker, "sessionToken", "test-token");
        Field field = tracker.getClass().getDeclaredField("botw");
        field.setAccessible(true);
        Object context = field.get(tracker);
        set(context, "eventId", 7);
        set(context, "metric", "GOTR");
        set(context, "status", "ACTIVE");
        set(context, "endTime", Long.MAX_VALUE);
        ChatMessage message = new ChatMessage();
        message.setMessage("Amount of rifts you have closed: 1,627.");
        tracker.onChatMessage(message);
        verify(api).postCompetitionProgress(eq("BOTW"), eq("test-token"), eq(7), eq(1L), eq(1627L), anyLong(), anyString());
        // Simulate acknowledgement, then replay the same count and a later count.
        set(context, "sending", false);
        set(context, "ackedValue", 1627L);
        tracker.onChatMessage(message);
        verifyNoMoreInteractions(api);
        message.setMessage("Amount of rifts you have closed: 1,630.");
        tracker.onChatMessage(message);
        verify(api).postCompetitionProgress(eq("BOTW"), eq("test-token"), eq(7), eq(3L), eq(1630L), anyLong(), anyString());
    }
}
