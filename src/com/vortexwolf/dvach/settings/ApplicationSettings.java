package com.vortexwolf.dvach.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;

import com.vortexwolf.dvach.R;
import com.vortexwolf.dvach.common.Constants;
import com.vortexwolf.dvach.common.library.Tracker;
import com.vortexwolf.dvach.common.utils.StringUtils;

public class ApplicationSettings implements SharedPreferences.OnSharedPreferenceChangeListener {

	private final SharedPreferences mSettings;
	private final Resources mResources;
	private final Tracker mTracker;
	private final ICacheSettingsChangedListener mCacheSettingsChangedListener;
	
	public ApplicationSettings(Context context, Resources resources, Tracker tracker, ICacheSettingsChangedListener cacheChangedListener) {
		this.mSettings = PreferenceManager.getDefaultSharedPreferences(context);
		this.mResources = resources;
		this.mTracker = tracker;
		this.mCacheSettingsChangedListener = cacheChangedListener;
	}
	
	public void startTrackChanges(){
		this.mSettings.registerOnSharedPreferenceChangeListener(this);	
	}
	
	public void stopTrackChanges(){
		this.mSettings.unregisterOnSharedPreferenceChangeListener(this);	
	}
		
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
						
		if(key.equals(mResources.getString(R.string.pref_theme_key))){
			String value = mSettings.getString(key, null);
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_THEME, value);
		}
		else if(key.equals(mResources.getString(R.string.pref_text_size_key))){
			String value = mSettings.getString(key, null);
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_TEXT_SIZE, value);
		}
		else if(key.equals(mResources.getString(R.string.pref_homepage_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_HOME_PAGE, mSettings.getString(key, "").toLowerCase());
		}
		else if(key.equals(mResources.getString(R.string.pref_load_thumbnails_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_LOAD_THUMBNAILS, String.valueOf(isLoadThumbnails()));
		}
		else if(key.equals(mResources.getString(R.string.pref_display_post_date_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_DISPLAY_DATE, String.valueOf(isDisplayPostItemDate()));
		}
		else if(key.equals(mResources.getString(R.string.pref_popup_link_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_POPUP_LINK, String.valueOf(isLinksInPopup()));
		}
		else if(key.equals(mResources.getString(R.string.pref_auto_refresh_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_AUTO_REFRESH, String.valueOf(isAutoRefresh()), getAutoRefreshInterval());
		}
		else if(key.equals(mResources.getString(R.string.pref_display_navigation_bar_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_NAVIGATION_BAR, String.valueOf(isDisplayNavigationBar()));
		}
		else if(key.equals(mResources.getString(R.string.pref_youtube_mobile_links_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_YOUTUBE_MOBILE, String.valueOf(isYoutubeMobileLinks()));
		}
		else if(key.equals(mResources.getString(R.string.pref_file_cache_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_FILE_CACHE, String.valueOf(isFileCacheEnabled()));
			if(this.mCacheSettingsChangedListener != null){
				mCacheSettingsChangedListener.cacheFileSystemChanged(isFileCacheEnabled());
			}
		}
		else if(key.equals(mResources.getString(R.string.pref_file_cache_sdcard_key))){
			mTracker.trackEvent(Tracker.CATEGORY_PREFERENCES, Tracker.ACTION_PREFERENCE_FILE_CACHE_SD, String.valueOf(isFileCacheSdCard()));
			if(this.mCacheSettingsChangedListener != null){
				mCacheSettingsChangedListener.cacheSDCardChanged(isFileCacheSdCard());
			}
		}
	}
	

	public String getHomepage(){
		String boardName = mSettings.getString(mResources.getString(R.string.pref_homepage_key), Constants.DEFAULT_BOARD).toLowerCase();
		return !StringUtils.isEmpty(boardName) ? boardName : Constants.DEFAULT_BOARD;
	}
	
	public boolean isLoadThumbnails() {
		return mSettings.getBoolean(mResources.getString(R.string.pref_load_thumbnails_key), true);
	}
	
	public boolean isDisplayPostItemDate() {
		return mSettings.getBoolean(mResources.getString(R.string.pref_display_post_date_key), false);
	}
	
	public boolean isLinksInPopup(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_popup_link_key), true);
	}
	
	public boolean isDisplayNavigationBar(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_display_navigation_bar_key), true);
	}
	
	public boolean isFileCacheEnabled(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_file_cache_key), true);
	}
	
	public boolean isFileCacheSdCard(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_file_cache_sdcard_key), true);
	}
	
	public boolean isAutoRefresh(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_auto_refresh_key), false);
	}
	
	public int getAutoRefreshInterval(){
		return mSettings.getInt(mResources.getString(R.string.pref_auto_refresh_interval_key), 60);
	}
	
	public boolean isYoutubeMobileLinks(){
		return mSettings.getBoolean(mResources.getString(R.string.pref_youtube_mobile_links_key), false);
	}
	
	public int getTheme(){
		final String defaultTextSizeValue = mResources.getString(R.string.pref_text_size_default_value);
		final String defaultThemeValue = mResources.getString(R.string.pref_theme_default_value);

		String theme = mSettings.getString(mResources.getString(R.string.pref_theme_key), defaultThemeValue);
		String textSize = mSettings.getString(mResources.getString(R.string.pref_text_size_key), defaultTextSizeValue);
		
		if(theme.equals(defaultThemeValue)){
			if(textSize.equals(defaultTextSizeValue))
				return R.style.Theme_Light_Medium;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_large_value)))
				return R.style.Theme_Light_Large;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_larger_value)))
				return R.style.Theme_Light_Larger;
			else if(textSize.equals(mResources.getString(R.string.pref_text_size_huge_value)))
				return R.style.Theme_Light_Huge;
			
			return R.style.Theme_Light_Medium;
		}
		else if(theme.equals(mResources.getString(R.string.pref_theme_dark_value))){
			if(textSize.equals(defaultTextSizeValue))
				return R.style.Theme_Dark_Medium;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_large_value)))
				return R.style.Theme_Dark_Large;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_larger_value)))
				return R.style.Theme_Dark_Larger;
			else if(textSize.equals(mResources.getString(R.string.pref_text_size_huge_value)))
				return R.style.Theme_Dark_Huge;
			
			return R.style.Theme_Dark_Medium;
		}
		else if(theme.equals(mResources.getString(R.string.pref_theme_photon_value))){
			if(textSize.equals(defaultTextSizeValue))
				return R.style.Theme_Photon_Medium;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_large_value)))
				return R.style.Theme_Photon_Large;
			else if (textSize.equals(mResources.getString(R.string.pref_text_size_larger_value)))
				return R.style.Theme_Photon_Larger;
			else if(textSize.equals(mResources.getString(R.string.pref_text_size_huge_value)))
				return R.style.Theme_Photon_Huge;
			
			return R.style.Theme_Photon_Medium;
		}
		
		return R.style.Theme_Light_Medium;
	}
	
	public SettingsEntity getCurrentSettings(){
		SettingsEntity result = new SettingsEntity();
		result.theme = this.getTheme();
		result.isDisplayDate = this.isDisplayPostItemDate();
		result.isLoadThumbnails = this.isLoadThumbnails();
		
		return result;
	}
}
