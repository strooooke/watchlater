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

package com.lambdasoup.watchlater;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.IdRes;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;

import retrofit.Callback;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.OkClient;
import retrofit.client.Response;
import retrofit.converter.GsonConverter;


public class AddActivity extends Activity {

	private static final String SCOPE_YOUTUBE = "oauth2:https://www.googleapis.com/auth/youtube";
	// fields are not final to be somewhat accessible for testing to inject other values
	@SuppressWarnings("FieldCanBeLocal")
	private static String YOUTUBE_ENDPOINT = "https://www.googleapis.com/youtube/v3";
	private static String ACCOUNT_TYPE_GOOGLE = "com.google";
	private AccountManager manager;
	private YoutubeApi api;

	private Account account;
	private String token;
	private String playlistId;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setDialogBehaviour();

		setContentView(R.layout.activity_add);

		manager = AccountManager.get(this);
		setApiAdapter();
		addToWatchLater();
	}

	private void setDialogBehaviour() {
		requestWindowFeature(Window.FEATURE_ACTION_BAR);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND,
				WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		WindowManager.LayoutParams params = getWindow().getAttributes();
		params.height = WindowManager.LayoutParams.WRAP_CONTENT;
		params.width = getResources().getDimensionPixelSize(R.dimen.dialog_width);
		params.alpha = 1.0f;
		params.dimAmount = 0.5f;
		getWindow().setAttributes(params);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu_add, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_about:
				showAbout();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	private void showAbout() {
		startActivity(new Intent(this, AboutActivity.class));
	}

	private void addToWatchLater() {

		showProgress();

		if (account == null) {
			setGoogleAccountAndRetry();
			return;
		}

		if (token == null) {
			setAuthTokenAndRetry();
			return;
		}

		if (playlistId == null) {
			setPlaylistIdAndRetry();
			return;
		}

		YoutubeApi.ResourceId resourceId = new YoutubeApi.ResourceId(getVideoId());
		YoutubeApi.Snippet snippet = new YoutubeApi.Snippet(playlistId, resourceId);
		YoutubeApi.PlaylistItem item = new YoutubeApi.PlaylistItem(snippet);

		api.insertPlaylistItem(item, new ErrorHandlingCallback<YoutubeApi.PlaylistItem>() {
			@Override
			public void success(YoutubeApi.PlaylistItem playlistItem, Response response) {
				onSuccess();
			}
		});
	}

	private String getVideoId() {
		Uri uri = getIntent().getData();

		// eg. https://www.youtube.com/watch?v=jqxENMKaeCU
		String videoId = uri.getQueryParameter("v");
		if (videoId != null) {
			return videoId;
		}

		// eg. http://youtu.be/jqxENMKaeCU
		return uri.getLastPathSegment();
	}

	private void setAuthTokenAndRetry() {
		manager.getAuthToken(account, SCOPE_YOUTUBE, null, this, new AccountManagerCallback<Bundle>() {
			@Override
			public void run(AccountManagerFuture<Bundle> future) {
				try {
					Bundle result = future.getResult();
					token = result.getString(AccountManager.KEY_AUTHTOKEN);
					addToWatchLater();
				} catch (OperationCanceledException e) {
					onError(ErrorType.NEED_ACCESS);
				} catch (IOException e) {
					onError(ErrorType.NETWORK);
				} catch (AuthenticatorException e) {
					onError(ErrorType.OTHER);
				}
			}
		}, null);
	}

	private void setGoogleAccountAndRetry() {
		Account[] accounts = manager.getAccountsByType(ACCOUNT_TYPE_GOOGLE);

		if (accounts.length != 1) {
			onMultipleAccounts();
			return;
		}

		this.account = accounts[0];

		addToWatchLater();
	}

	private void onMultipleAccounts() {
		final ArrayAdapter<Account> adapter = new ArrayAdapter<Account>(this, R.layout.item_account, manager.getAccountsByType(ACCOUNT_TYPE_GOOGLE)) {
			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				TextView accountName;
				if (convertView != null) {
					accountName = (TextView) convertView;
				} else {
					accountName = (TextView) getLayoutInflater().inflate(R.layout.item_account, parent, false);
				}
				accountName.setText(getItem(position).name);
				return accountName;
			}
		};
		final ListView listView = (ListView) findViewById(R.id.account_list);
		listView.setAdapter(adapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				onAccountChosen(adapter.getItem(position - listView.getHeaderViewsCount()));
			}
		});
		View header = getLayoutInflater().inflate(R.layout.list_header_account_chooser, listView, false);
		listView.addHeaderView(header);
		listView.setEmptyView(findViewById(R.id.account_chooser_empty));

		showAccountChooser();
	}

	private void onAccountChosen(Account account) {
		this.account = account;
		addToWatchLater();
	}

	private void setApiAdapter() {
		Gson gson = new GsonBuilder().create();

		RestAdapter.Builder builder = new RestAdapter.Builder()
				.setClient(new OkClient())
				.setConverter(new GsonConverter(gson))
				.setRequestInterceptor(new RequestInterceptor() {
					@Override
					public void intercept(RequestFacade request) {
						request.addHeader("Authorization", "Bearer " + token);
					}
				})
				.setEndpoint(YOUTUBE_ENDPOINT);

		if (BuildConfig.DEBUG) {
			builder.setLogLevel(RestAdapter.LogLevel.FULL);
		}

		RestAdapter adapter = builder.build();
		api = adapter.create(YoutubeApi.class);
	}

	private void onError(ErrorType type) {
		int msgId;
		switch (type) {
			case NEED_ACCESS:
				msgId = R.string.error_need_account;
				break;
			case OTHER:
			case NETWORK:
				msgId = R.string.error_other;
				break;
			case PLAYLIST_FULL:
				msgId = R.string.error_playlist_full;
				break;
			default:
				throw new IllegalArgumentException("unexpected error type: " + type);
		}

		if (isFinishing()) {
			showToast(msgId);
			return;
		}

		TextView errorMsg = (TextView) findViewById(R.id.error_msg);
		errorMsg.setText(msgId);

		showError();
	}

	private void onSuccess() {
		if (isFinishing()) {
			showToast(R.string.success_added_video);
			return;
		}

		showSuccess();
	}

	private void showAccountChooser() {
		showView(R.id.account_chooser);
	}

	private void showError() {
		showView(R.id.error);
	}

	private void showSuccess() {
		showView(R.id.success);
	}

	private void showProgress() {
		showView(R.id.progress);
	}

	private void showView(@IdRes int id) {
		ViewAnimator animator = (ViewAnimator) findViewById(R.id.animator);

		if (animator.getCurrentView().getId() == id) {
			return;
		}

		for (int i = 0; i < animator.getChildCount(); i++) {
			View child = animator.getChildAt(i);
			if (child.getId() == id) {
				animator.setDisplayedChild(i);
				return;
			}
		}

		throw new IllegalArgumentException("animator does not have a child with id " + id);
	}

	private void showToast(int msgId) {
		Toast.makeText(this, msgId, Toast.LENGTH_SHORT).show();
	}

	public void onRetry(View v) {
		addToWatchLater();
	}

	private void setPlaylistIdAndRetry() {
		api.listMyChannels(new ErrorHandlingCallback<YoutubeApi.Channels>() {
			@Override
			public void success(YoutubeApi.Channels channels, Response response) {
				playlistId = channels.items.get(0).contentDetails.relatedPlaylists.watchLater;
				addToWatchLater();
			}
		});
	}

	private void onTokenInvalid() {
		manager.invalidateAuthToken(account.type, token);
		token = null;
		addToWatchLater();
	}

	private enum ErrorType {
		NEED_ACCESS, NETWORK, OTHER, PLAYLIST_FULL
	}

	private abstract class ErrorHandlingCallback<T> implements Callback<T> {
		@Override
		public void failure(RetrofitError error) {
			if (error.getResponse() == null) {
				onError(ErrorType.NETWORK);
				return;
			}

			switch (error.getResponse().getStatus()) {
				case 401:
					onTokenInvalid();
					break;
				case 403:
					onError(ErrorType.PLAYLIST_FULL);
					break;
				default:
					onError(ErrorType.OTHER);
			}

		}
	}

}
