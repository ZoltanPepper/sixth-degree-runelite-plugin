package com.sixthdegree;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class SixthDegreePluginSessionValidationTest
{
	private static void set(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void validate(SixthDegreePlugin plugin) throws Exception
	{
		Method method = SixthDegreePlugin.class.getDeclaredMethod("validateSession", String.class, String.class);
		method.setAccessible(true);
		method.invoke(plugin, "Player", "token");
	}

	private static SixthDegreePlugin plugin(
		SixthDegreePanel panel,
		SixthDegreeApiClient apiClient,
		boolean sessionValid) throws Exception
	{
		SixthDegreePlugin plugin = new SixthDegreePlugin();
		set(plugin, "panel", panel);
		set(plugin, "apiClient", apiClient);
		set(plugin, "sessionValid", sessionValid);
		set(plugin, "validatedRsn", sessionValid ? "Player" : null);
		return plugin;
	}

	@Test
	public void backgroundValidationPreservesTheVisiblePanel() throws Exception
	{
		SixthDegreePanel panel = mock(SixthDegreePanel.class);
		SixthDegreeApiClient apiClient = mock(SixthDegreeApiClient.class);
		when(apiClient.getMemberStatus("token")).thenReturn(new CompletableFuture<>());

		validate(plugin(panel, apiClient, true));

		verify(panel, never()).showCheckingAccess(anyString());
	}

	@Test
	public void initialValidationStillShowsTheAccessCheck() throws Exception
	{
		SixthDegreePanel panel = mock(SixthDegreePanel.class);
		SixthDegreeApiClient apiClient = mock(SixthDegreeApiClient.class);
		when(apiClient.getMemberStatus("token")).thenReturn(new CompletableFuture<>());

		validate(plugin(panel, apiClient, false));

		verify(panel).showCheckingAccess("Player");
	}
}
