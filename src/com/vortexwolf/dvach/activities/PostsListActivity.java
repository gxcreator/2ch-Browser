package com.vortexwolf.dvach.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.ClipboardManager;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;

import com.vortexwolf.dvach.R;
import com.vortexwolf.dvach.adapters.PostsListAdapter;
import com.vortexwolf.dvach.asynctasks.DownloadFileTask;
import com.vortexwolf.dvach.asynctasks.DownloadPostsTask;
import com.vortexwolf.dvach.common.Constants;
import com.vortexwolf.dvach.common.Factory;
import com.vortexwolf.dvach.common.MainApplication;
import com.vortexwolf.dvach.common.library.MyLog;
import com.vortexwolf.dvach.common.utils.AppearanceUtils;
import com.vortexwolf.dvach.common.utils.CompatibilityUtils;
import com.vortexwolf.dvach.common.utils.StringUtils;
import com.vortexwolf.dvach.common.utils.UriUtils;
import com.vortexwolf.dvach.db.FavoritesDataSource;
import com.vortexwolf.dvach.interfaces.IJsonApiReader;
import com.vortexwolf.dvach.interfaces.IOpenTabsManager;
import com.vortexwolf.dvach.interfaces.IPagesSerializationService;
import com.vortexwolf.dvach.interfaces.IPostsListView;
import com.vortexwolf.dvach.models.domain.PostInfo;
import com.vortexwolf.dvach.models.domain.PostsList;
import com.vortexwolf.dvach.models.presentation.AttachmentInfo;
import com.vortexwolf.dvach.models.presentation.OpenTabModel;
import com.vortexwolf.dvach.models.presentation.PostItemViewModel;
import com.vortexwolf.dvach.services.BrowserLauncher;
import com.vortexwolf.dvach.services.TimerService;
import com.vortexwolf.dvach.services.Tracker;
import com.vortexwolf.dvach.services.presentation.DvachUriBuilder;
import com.vortexwolf.dvach.services.presentation.ListViewScrollListener;
import com.vortexwolf.dvach.settings.ApplicationPreferencesActivity;
import com.vortexwolf.dvach.settings.ApplicationSettings;
import com.vortexwolf.dvach.settings.SettingsEntity;

public class PostsListActivity extends BaseListActivity {
    private static final String TAG = "PostsListActivity";

    private MainApplication mApplication;
    private ApplicationSettings mSettings;
    private IJsonApiReader mJsonReader;
    private Tracker mTracker;
    private IPagesSerializationService mSerializationService;
    private DvachUriBuilder mDvachUriBuilder;
    private FavoritesDataSource mFavoritesDatasource;
    private IOpenTabsManager mTabsManager;

    private PostsListAdapter mAdapter = null;
    private DownloadPostsTask mCurrentDownloadTask = null;
    private TimerService mAutoRefreshTimer = null;
    private final PostsReaderListener mPostsReaderListener = new PostsReaderListener();

    private SettingsEntity mCurrentSettings;

    private OpenTabModel mTabModel;
    private String mBoardName;
    private String mThreadNumber;
    private String mPostNumber = null;

    private Menu mMenu;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.mApplication = (MainApplication) this.getApplication();

        // Парсим код доски и номер страницы
        Uri data = this.getIntent().getData();
        if (data != null) {
            this.mBoardName = UriUtils.getBoardName(data);
            this.mThreadNumber = UriUtils.getThreadNumber(data);
            this.mPostNumber = data.getFragment();
        }

        this.mSettings = this.mApplication.getSettings();
        this.mJsonReader = this.mApplication.getJsonApiReader();
        this.mCurrentSettings = this.mSettings.getCurrentSettings();
        this.mTracker = this.mApplication.getTracker();
        this.mSerializationService = this.mApplication.getSerializationService();
        this.mDvachUriBuilder = Factory.getContainer().resolve(DvachUriBuilder.class);
        this.mTabsManager = this.mApplication.getOpenTabsManager();
        this.mFavoritesDatasource = Factory.getContainer().resolve(FavoritesDataSource.class);

        // Page title and new tab
        String pageSubject = this.getIntent().hasExtra(Constants.EXTRA_THREAD_SUBJECT)
                ? this.getIntent().getExtras().getString(Constants.EXTRA_THREAD_SUBJECT)
                : null;

        OpenTabModel tabModel = new OpenTabModel(pageSubject, Uri.parse(this.mDvachUriBuilder.create2chThreadUrl(this.mBoardName, this.mThreadNumber)));
        this.mTabModel = this.mTabsManager.add(tabModel);
        
        this.updateTitle(pageSubject);

        this.resetUI();

        this.setAdapter(savedInstanceState);

        final Runnable refreshTask = new Runnable() {
            @Override
            public void run() {
                MyLog.v(TAG, "Attempted to refresh");
                if (PostsListActivity.this.mCurrentDownloadTask == null) {
                    PostsListActivity.this.refreshPosts();
                }
            }
        };

        this.mAutoRefreshTimer = new TimerService(this.mSettings.isAutoRefresh(), this.mSettings.getAutoRefreshInterval(), refreshTask, this);
        this.mAutoRefreshTimer.start();

        this.mTracker.setBoardVar(this.mBoardName);
        this.mTracker.trackActivityView(TAG);
    }

    @Override
    protected void onDestroy() {
        this.mAutoRefreshTimer.stop();

        MyLog.d(TAG, "Destroyed");

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        this.mTabModel.setPosition(AppearanceUtils.getCurrentListPosition(this.getListView()));

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Проверяем изменение настроек
        SettingsEntity newSettings = this.mSettings.getCurrentSettings();
        if (this.mCurrentSettings.theme != newSettings.theme) {
            this.finish();
            Intent i = new Intent(this.getIntent());
            i.putExtra(Constants.EXTRA_PREFER_DESERIALIZED, true);
            this.startActivity(i);
            return;
        }

        if (this.mCurrentSettings.isDisplayDate != newSettings.isDisplayDate 
            || this.mCurrentSettings.isLoadThumbnails != newSettings.isLoadThumbnails
            || this.mCurrentSettings.isLocalDate != newSettings.isLocalDate) {
            this.mAdapter.notifyDataSetChanged();
        }

        this.mAutoRefreshTimer.update(this.mSettings.isAutoRefresh(), this.mSettings.getAutoRefreshInterval());

        this.mCurrentSettings = newSettings;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.posts_list_view;
    }

    @Override
    protected void resetUI() {
        super.resetUI();

        CompatibilityUtils.setDisplayHomeAsUpEnabled(this);
        this.registerForContextMenu(this.getListView());
    }

    private void setAdapter(Bundle savedInstanceState) {
        if (this.mAdapter != null) {
            return;
        }

        this.mAdapter = new PostsListAdapter(this, this.mBoardName, this.mThreadNumber, this.mApplication.getBitmapManager(), this.mApplication.getSettings(), this.getTheme(), this.getListView(), this.mDvachUriBuilder);
        this.setListAdapter(this.mAdapter);

        // добавляем обработчик, чтобы не рисовать картинки во время прокрутки
        if (Integer.valueOf(Build.VERSION.SDK) > 7) {
            this.getListView().setOnScrollListener(new ListViewScrollListener(this.mAdapter));
        }

        boolean preferDeserialized = this.getIntent().hasExtra(Constants.EXTRA_PREFER_DESERIALIZED)
                                || savedInstanceState != null && savedInstanceState.containsKey(Constants.EXTRA_PREFER_DESERIALIZED);
        
        LoadPostsTask task = new LoadPostsTask(preferDeserialized);
        task.execute();
    }
    
    private void setAdapterData(PostInfo[] posts){
        boolean isFirstTime = this.mAdapter.isEmpty();
        
        this.mAdapter.setAdapterData(posts);
        this.updateTitle(this.mAdapter.getItem(0).getSubjectOrText());
        
        if (isFirstTime) {
            if (this.mPostNumber != null) {
                this.mAdapter.scrollToPost(this.mPostNumber);
            } else {
                // Устанавливаем позицию, если открываем как уже открытую вкладку
                AppearanceUtils.ListViewPosition savedPosition = this.mTabModel.getPosition();
                if (savedPosition != null) {
                    this.getListView().setSelectionFromTop(savedPosition.position, savedPosition.top);
                }
            }
        }
    }
    
    private void updateTitle(String title){
        String pageTitle = title != null
                ? String.format(this.getString(R.string.data_thread_withsubject_title), this.mBoardName, title)
                : String.format(this.getString(R.string.data_thread_title), this.mBoardName, this.mThreadNumber);

        this.setTitle(pageTitle);
        
        String tabTitle = title != null ? title : pageTitle;
        this.mTabModel.setTitle(tabTitle);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = this.getMenuInflater();
        inflater.inflate(R.menu.thread, menu);

        this.mMenu = menu;
        this.updateOptionsMenu();

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.tabs_menu_id:
                Intent openTabsIntent = new Intent(this.getApplicationContext(), TabsHistoryBookmarksActivity.class);
                openTabsIntent.putExtra(Constants.EXTRA_CURRENT_URL, this.mTabModel.getUri().toString());
                this.startActivity(openTabsIntent);
                break;
            case R.id.refresh_menu_id:
                this.refreshPosts();
                break;
            case R.id.pick_board_menu_id:
                // Start new activity
                Intent pickBoardIntent = new Intent(this.getApplicationContext(), PickBoardActivity.class);
                this.startActivityForResult(pickBoardIntent, Constants.REQUEST_CODE_PICK_BOARD_ACTIVITY);
                break;
            case R.id.open_browser_menu_id:
                BrowserLauncher.launchExternalBrowser(this, this.mDvachUriBuilder.create2chThreadUrl(this.mBoardName, this.mThreadNumber), true);
                break;
            case R.id.preferences_menu_id:
                // Start new activity
                Intent preferencesIntent = new Intent(this.getApplicationContext(), ApplicationPreferencesActivity.class);
                this.startActivity(preferencesIntent);
                break;
            case R.id.add_menu_id:
                this.navigateToAddPostView(null, null);
                break;
            case R.id.share_menu_id:
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, this.mTabModel.getTitle());
                i.putExtra(Intent.EXTRA_TEXT, this.mTabModel.getUri().toString());
                this.startActivity(Intent.createChooser(i, this.getString(R.string.share_via)));
                break;
            case R.id.add_remove_favorites_menu_id:
                String url = this.mTabModel.getUri().toString();
                if (this.mFavoritesDatasource.hasFavorites(url)) {
                    this.mFavoritesDatasource.removeFromFavorites(url);
                } else {
                    this.mFavoritesDatasource.addToFavorites(this.mTabModel.getTitle(), url);
                }

                this.updateOptionsMenu();

                break;
            case android.R.id.home:
                this.navigateToThreads(this.mBoardName);
                break;
        }

        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        PostItemViewModel item = this.mAdapter.getItem(info.position);

        menu.add(Menu.NONE, Constants.CONTEXT_MENU_REPLY_POST, 0, this.getString(R.string.cmenu_reply_post));
        if (!StringUtils.isEmpty(item.getSpannedComment().toString())) {
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_REPLY_POST_QUOTE, 1, this.getString(R.string.cmenu_reply_post_quote));
        }
        if (!StringUtils.isEmpty(item.getSpannedComment())) {
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_COPY_TEXT, 2, this.getString(R.string.cmenu_copy_post));
        }
        if (item.hasAttachment() && item.getAttachment(this.mBoardName).isFile()) {
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_DOWNLOAD_FILE, 3, this.getString(R.string.cmenu_download_file));
        }
        if (item.hasAttachment() && item.getAttachment(this.mBoardName).isImage()) {
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_SEARCH_IMAGE, 4, this.getString(R.string.cmenu_search_image));
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_SEARCH_IMAGE_GOOGLE, 5, this.getString(R.string.cmenu_search_image_google));
        }
        if (!StringUtils.isEmpty(item.getSpannedComment())) {
            menu.add(Menu.NONE, Constants.CONTEXT_MENU_SHARE, 6, this.getString(R.string.cmenu_share));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        PostItemViewModel info = this.mAdapter.getItem(menuInfo.position);

        switch (item.getItemId()) {
            case Constants.CONTEXT_MENU_REPLY_POST:
                this.navigateToAddPostView(info.getNumber(), null);
                break;
            case Constants.CONTEXT_MENU_REPLY_POST_QUOTE:
                this.navigateToAddPostView(info.getNumber(), info.getSpannedComment().toString());
                break;
            case Constants.CONTEXT_MENU_COPY_TEXT:
                ClipboardManager clipboard = (ClipboardManager) this.getSystemService(CLIPBOARD_SERVICE);
                clipboard.setText(info.getSpannedComment().toString());

                AppearanceUtils.showToastMessage(this, this.getString(R.string.notification_post_copied));
                break;
            case Constants.CONTEXT_MENU_DOWNLOAD_FILE:
                AttachmentInfo attachment = info.getAttachment(this.mBoardName);
                Uri fileUri = Uri.parse(attachment.getSourceUrl(this.mSettings));
                new DownloadFileTask(this, fileUri).execute();
                break;
            case Constants.CONTEXT_MENU_SEARCH_IMAGE:
                //String imageUrl = info.getAttachment(this.mBoardName).getSourceUrl(this.mSettings);
                //new SearchImageTask(imageUrl, this.getApplicationContext(), MainApplication.getHttpClient()).execute();
                String imageUrl1 = info.getAttachment(this.mBoardName).getSourceUrl(this.mSettings);
                String tineyeSearchUrl = "http://www.tineye.com/search?url=" + imageUrl1;
                BrowserLauncher.launchExternalBrowser(this.getApplicationContext(), tineyeSearchUrl);
                break;
            case Constants.CONTEXT_MENU_SEARCH_IMAGE_GOOGLE:
                String imageUrl2 = info.getAttachment(this.mBoardName).getSourceUrl(this.mSettings);
                String googleSearchUrl = "http://www.google.com/searchbyimage?image_url=" + imageUrl2;
                BrowserLauncher.launchExternalBrowser(this.getApplicationContext(), googleSearchUrl);
                break;
            case Constants.CONTEXT_MENU_SHARE:
                Intent shareLinkIntent = new Intent(Intent.ACTION_SEND);
                shareLinkIntent.setType("text/plain");
                shareLinkIntent.putExtra(Intent.EXTRA_SUBJECT, this.mTabModel.getUri().toString() + "#" + info.getNumber());
                shareLinkIntent.putExtra(Intent.EXTRA_TEXT, info.getSpannedComment().toString());
                this.startActivity(Intent.createChooser(shareLinkIntent, this.getString(R.string.share_via)));
                break;
        }

        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(Constants.EXTRA_PREFER_DESERIALIZED, true);

        super.onSaveInstanceState(outState);
    }

    private void updateOptionsMenu() {
        if (this.mMenu == null) {
            return;
        }

        MenuItem favoritesItem = this.mMenu.findItem(R.id.add_remove_favorites_menu_id);
        if (this.mFavoritesDatasource.hasFavorites(this.mTabModel.getUri().toString())) {
            favoritesItem.setTitle(R.string.menu_remove_favorites);
        } else {
            favoritesItem.setTitle(R.string.menu_add_favorites);
        }
    }

    private void navigateToAddPostView(String postNumber, String postComment) {
        Intent addPostIntent = new Intent(this.getApplicationContext(), AddPostActivity.class);
        addPostIntent.putExtra(Constants.EXTRA_BOARD_NAME, this.mBoardName);
        addPostIntent.putExtra(Constants.EXTRA_THREAD_NUMBER, this.mThreadNumber);

        if (postNumber != null) {
            addPostIntent.putExtra(Constants.EXTRA_POST_NUMBER, postNumber);
        }
        if (postComment != null) {
            addPostIntent.putExtra(Constants.EXTRA_POST_COMMENT, postComment);
        }

        this.startActivityForResult(addPostIntent, Constants.REQUEST_CODE_ADD_POST_ACTIVITY);
    }

    private void navigateToThreads(String boardName) {
        Intent i = new Intent(this.getApplicationContext(), ThreadsListActivity.class);
        i.setData(this.mDvachUriBuilder.create2chBoardUri(boardName, 0));
        this.startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case Constants.REQUEST_CODE_ADD_POST_ACTIVITY:
                    this.refreshPosts();
                    break;
                case Constants.REQUEST_CODE_PICK_BOARD_ACTIVITY: {
                    String boardCode = intent.getExtras().getString(Constants.EXTRA_SELECTED_BOARD);

                    this.navigateToThreads(boardCode);
                    break;
                }
            }
        }
    }

    private void refreshPosts() {
        this.refreshPosts(true);
    }

    private void refreshPosts(boolean checkModified) {
        if (this.mCurrentDownloadTask != null) {
            this.mCurrentDownloadTask.cancel(true);
        }

        if (!this.mAdapter.isEmpty()) {
            // load new posts
            this.mCurrentDownloadTask = new DownloadPostsTask(this.mPostsReaderListener, this.mBoardName, this.mThreadNumber, true, this.mJsonReader, true);
            this.mCurrentDownloadTask.execute(this.mAdapter.getLastPostNumber());
        } else {
            this.mCurrentDownloadTask = new DownloadPostsTask(this.mPostsReaderListener, this.mBoardName, this.mThreadNumber, checkModified, this.mJsonReader, false);
            this.mCurrentDownloadTask.execute();
        }
    }
    
    private class LoadPostsTask extends AsyncTask<Void, Long, PostInfo[]> {
        private boolean mPreferDeserialized;
        public LoadPostsTask(boolean preferDeserialized){
            this.mPreferDeserialized = preferDeserialized;
        }
        
        @Override
        protected PostInfo[] doInBackground(Void... arg0) {
            // Пробуем десериализовать в любом случае
            PostInfo[] posts = mSerializationService.deserializePosts(mBoardName, mThreadNumber);
            return posts;
        }   
        
        @Override
        public void onPreExecute() {
            mPostsReaderListener.showLoadingScreen();
        }

        @Override
        public void onPostExecute(PostInfo[] posts) {
            mPostsReaderListener.hideLoadingScreen();

            if (posts != null) {
                PostsListActivity.this.setAdapterData(posts);
                
                // Обновляем посты, если не был установлен ограничивающий extra
                if (this.mPreferDeserialized) {
                    // nothing
                }
                else {
                    PostsListActivity.this.refreshPosts();
                }
            } else {
                PostsListActivity.this.refreshPosts(false);
            }
        }
    }

    private class PostsReaderListener implements IPostsListView {

        @Override
        public Context getApplicationContext() {
            return PostsListActivity.this.getApplicationContext();
        }

        @Override
        public void setWindowProgress(int value) {
            PostsListActivity.this.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, value);
        }

        @Override
        public void setData(PostsList postsList) {
            if (postsList != null) {
                PostInfo[] posts = postsList.getThread();
                PostsListActivity.this.mSerializationService.serializePosts(PostsListActivity.this.mBoardName, PostsListActivity.this.mThreadNumber, posts);
                PostsListActivity.this.setAdapterData(posts);
            } else {
                PostsListActivity.this.mAdapter.clear();
                this.showError(PostsListActivity.this.getString(R.string.error_list_empty));
            }
        }

        @Override
        public void showError(String error) {
            PostsListActivity.this.switchToErrorView(error);
        }

        @Override
        public void showLoadingScreen() {
            PostsListActivity.this.switchToLoadingView();
        }

        @Override
        public void hideLoadingScreen() {
            PostsListActivity.this.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_INDETERMINATE_OFF);
            PostsListActivity.this.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_VISIBILITY_OFF);
            
            PostsListActivity.this.switchToListView();
            PostsListActivity.this.mCurrentDownloadTask = null;
        }

        @Override
        public void updateData(String from, PostsList list) {
            if (list == null) {
                AppearanceUtils.showToastMessage(PostsListActivity.this, PostsListActivity.this.getResources().getString(R.string.notification_no_new_posts));
                return;
            }
            
            PostInfo[] posts = list.getThread();

            int addedCount = PostsListActivity.this.mAdapter.updateAdapterData(from, posts);
            if (addedCount != 0) {
                // Нужно удостовериться, что элементы из posts не менялись после
                // добавления в адаптер, чтобы сериализация прошла правильно
                PostsListActivity.this.mSerializationService.serializePosts(PostsListActivity.this.mBoardName, PostsListActivity.this.mThreadNumber, posts);
                AppearanceUtils.showToastMessage(PostsListActivity.this, PostsListActivity.this.getResources().getQuantityString(R.plurals.data_new_posts_quantity, addedCount, addedCount));
            } else {
                AppearanceUtils.showToastMessage(PostsListActivity.this, PostsListActivity.this.getResources().getString(R.string.notification_no_new_posts));
            }
        }

        @Override
        public void showUpdateError(String error) {
            AppearanceUtils.showToastMessage(PostsListActivity.this, error);
        }

        @Override
        public void showUpdateLoading() {
            PostsListActivity.this.mAdapter.setLoadingMore(true);
        }

        @Override
        public void hideUpdateLoading() {
            PostsListActivity.this.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_INDETERMINATE_OFF);
            PostsListActivity.this.getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_VISIBILITY_OFF);
            
            PostsListActivity.this.mAdapter.setLoadingMore(false);
            PostsListActivity.this.mCurrentDownloadTask = null;
        }
    }
}
