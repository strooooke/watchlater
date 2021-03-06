/*
 * Copyright (c) 2015. Maximilian Hille <mh@lambdasoup.com>
 *
 * This file is part of Watch Later.
 *
 * Watch Later is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Watch Later is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Watch Later.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.lambdasoup.watchlater.test;/*
 * Copyright (c) 2015. Maximilian Hille <mh@lambdasoup.com>
 *
 * This file is part of Watch Later.
 *
 * Watch Later is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Watch Later is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Watch Later.  If not, see <http://www.gnu.org/licenses/>.
 */

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Intent;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.IdlingResource;
import android.test.ActivityInstrumentationTestCase2;

import com.lambdasoup.watchlater.AddActivity;
import com.lambdasoup.watchlater.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit.Profiler;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.Espresso.registerIdlingResources;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

/**
 * Integration test for the {@link com.lambdasoup.watchlater.AddActivity}
 */
public class AddActivityTest extends ActivityInstrumentationTestCase2<AddActivity> {

	private static final String MOCK_ENDPOINT = "http://localhost:8080/";
	private static final String TEST_ACCOUNT_TYPE = "com.lambdasoup.watchlater.test";

	private static final Account ACCOUNT_1 = new Account("test account 1", TEST_ACCOUNT_TYPE);
	private static final Account ACCOUNT_2 = new Account("test account 2", TEST_ACCOUNT_TYPE);
	private MockEndpoint mockEndpoint;

	public AddActivityTest() throws IOException {
		super(AddActivity.class);
	}

	@Override
	public void setUp() throws Exception {
		super.setUp();
		injectInstrumentation(InstrumentationRegistry.getInstrumentation());

		// inject retrofit profiler for espresso idling resource
		Field retrofitProfiler = AddActivity.class.getDeclaredField("OPTIONAL_RETROFIT_PROFILER");
		retrofitProfiler.setAccessible(true);
		RetrofitProfilerIdlingResource retrofitProfilerIdlingResource = new RetrofitProfilerIdlingResource();
		retrofitProfiler.set(AddActivity.class, retrofitProfilerIdlingResource);
		registerIdlingResources(retrofitProfilerIdlingResource);

		// inject test account type
		Field accountType = AddActivity.class.getDeclaredField("ACCOUNT_TYPE_GOOGLE");
		accountType.setAccessible(true);
		accountType.set(AddActivity.class, TEST_ACCOUNT_TYPE);

		// clear accounts
		AccountManager accountManager = AccountManager.get(getInstrumentation().getContext());
		//noinspection ResourceType,deprecation
		accountManager.removeAccount(ACCOUNT_1, null, null);
		//noinspection ResourceType,deprecation
		accountManager.removeAccount(ACCOUNT_2, null, null);

		// inject mock backend
		Field endpoint = AddActivity.class.getDeclaredField("YOUTUBE_ENDPOINT");
		endpoint.setAccessible(true);
		endpoint.set(AddActivity.class, MOCK_ENDPOINT);

		// clear mock handlers
		mockEndpoint = new MockEndpoint("localhost", 8080);
		mockEndpoint.start();
	}

	@Override
	protected void tearDown() throws Exception {
		mockEndpoint.stop();

		super.tearDown();
	}

	private void addAccount(Account account) {
		AccountManager accountManager = AccountManager.get(getInstrumentation().getContext());
		//noinspection ResourceType
		accountManager.addAccountExplicitly(account, null, null);
	}

	public void test_noAccount() {
		getActivity();

		onView(withText(R.string.no_account)).check(matches(isDisplayed()));
	}

	public void test_multipleAccounts() {
		addAccount(ACCOUNT_1);
		addAccount(ACCOUNT_2);

		getActivity();

		onView(withText(R.string.choose_account)).check(matches(isDisplayed()));
	}

	public void test_add() throws Exception {
		// set channel list response
		{
			JSONObject json = new JSONObject();
			JSONArray items = new JSONArray();
			json.put("items", items);
			JSONObject channel = new JSONObject();
			items.put(channel);
			JSONObject contentDetails = new JSONObject();
			channel.put("contentDetails", contentDetails);
			JSONObject relatedPlaylists = new JSONObject();
			contentDetails.put("relatedPlaylists", relatedPlaylists);
			String watchLaterId = "45h7394875w3495";
			relatedPlaylists.put("watchLater", watchLaterId);
			mockEndpoint.add("/channels", json.toString(8));
		}

		// set add video to list response
		{
			JSONObject json = new JSONObject();
			mockEndpoint.add("/playlistItems", json.toString(8));
		}

		// set account
		addAccount(ACCOUNT_1);

		// set activity arg
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com/v/8f7h837f4"));
		setActivityIntent(intent);

		getActivity();

		onView(withText(R.string.success_added_video)).check(matches(isDisplayed()));
	}

	private static class RetrofitProfilerIdlingResource implements IdlingResource, Profiler<Void> {

		private AtomicInteger requestCount = new AtomicInteger(0);
		private ResourceCallback idleTransitionCallback;

		@Override
		public String getName() {
			return RetrofitProfilerIdlingResource.class.getName();
		}

		@Override
		public boolean isIdleNow() {
			return requestCount.intValue() == 0;
		}

		@Override
		public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
			this.idleTransitionCallback = resourceCallback;
		}

		@Override
		public Void beforeCall() {
			requestCount.incrementAndGet();
			return null;
		}

		@Override
		public void afterCall(RequestInformation requestInfo, long elapsedTime, int statusCode, Void beforeCallData) {
			int newRequestCount = requestCount.decrementAndGet();

			if (newRequestCount == 0) {
				idleTransitionCallback.onTransitionToIdle();
			}
		}
	}

}
