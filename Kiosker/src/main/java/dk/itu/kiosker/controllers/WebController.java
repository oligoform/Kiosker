package dk.itu.kiosker.controllers;

import android.content.Intent;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import dk.itu.kiosker.activities.MainActivity;
import dk.itu.kiosker.activities.SettingsActivity;
import dk.itu.kiosker.models.Constants;
import dk.itu.kiosker.web.KioskerWebChromeClient;
import dk.itu.kiosker.web.KioskerWebViewClient;
import dk.itu.kiosker.web.NavigationLayout;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;

public class WebController {
    private MainActivity mainActivity;
    private Date lastTap;
    private int tapsToOpenSettings = 5;
    private int taps = tapsToOpenSettings;

    // Has our main web view (home) at index 0 and the sites at index 1
    private ArrayList<WebView> webViews;

    private ArrayList<String> homeWebPages;
    private ArrayList<String> sitesWebPages;
    private ArrayList<String> screenSaverWebPages;
    private ArrayList<Subscriber> subscribers;
    private int reloadPeriodMins;
    private int errorReloadMins;

    // Screen saver subscription
    private Subscriber screenSaverSubscriber;
    private Observable screenSaverObservable;
    private int screenSaveLengthMins;
    private boolean wasScreenSaving;

    private ArrayList<NavigationLayout> navigationLayouts;

    public WebController(MainActivity mainActivity, ArrayList<Subscriber> subscribers) {
        this.mainActivity = mainActivity;
        this.subscribers = subscribers;
        // Start our tap detection with a value-
        lastTap = new Date();
        // Contains two web views, the main view and the sites view.
        webViews = new ArrayList<>();
        homeWebPages = new ArrayList<>();
        sitesWebPages = new ArrayList<>();
        screenSaverWebPages = new ArrayList<>();
        navigationLayouts = new ArrayList<>();
    }

    public void handleWebSettings(LinkedHashMap settings) {
        setTimers(settings);
        int layout = (int) settings.get("layout");

        homeWebPages = (ArrayList<String>) settings.get("home");
        sitesWebPages = (ArrayList<String>) settings.get("sites");

        handleWebViewSetup(layout);
        handleAutoCycleSecondary(settings);
        handleScreenSaving(settings);
    }

    private void handleWebViewSetup(int layout) {
        if (!homeWebPages.isEmpty()) {
            float weight = layoutTranslator(layout, true);
            // If the weight to the main view is "below" fullscreen and there are alternative sites set the main view to fullscreen.
            if (weight < 1.0 && (sitesWebPages == null || sitesWebPages.isEmpty()))
                setupWebView(homeWebPages.get(0), homeWebPages.get(1), 1.0f);
            if (weight > 0.0)
                setupWebView(homeWebPages.get(0), homeWebPages.get(1), weight);
        }

        if (!sitesWebPages.isEmpty()) {
            float weight = layoutTranslator(layout, false);
            if (weight > 0.0)
                setupWebView(sitesWebPages.get(0), sitesWebPages.get(1), weight);
        }
    }

    private void handleAutoCycleSecondary(LinkedHashMap settings) {
        Boolean autoCycleSecondary = (Boolean) settings.get("autoCycleSecondary");
        if (autoCycleSecondary != null && autoCycleSecondary && sitesWebPages.size() > 2) {
            Integer autoCycleSecondaryPeriodMins = (Integer) settings.get("autoCycleSecondaryPeriodMins");
            if (autoCycleSecondaryPeriodMins != null) {
                Subscriber<Long> sitesCycleSubscriber = new Subscriber<Long>() {
                    int index = 0;

                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(Constants.TAG, "Error while cycling secondary screen.", e);
                    }

                    @Override
                    public void onNext(Long l) {
                        if (webViews.size() > 1) {
                            String url = sitesWebPages.get((index += 2) % sitesWebPages.size());
                            webViews.get(1).loadUrl(url);
                        } else
                            unsubscribe();
                    }
                };
                // Add our subscriber to subscribers so that we can cancel it later
                subscribers.add(sitesCycleSubscriber);
                Observable.timer(autoCycleSecondaryPeriodMins, TimeUnit.MINUTES)
                        .repeat()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(sitesCycleSubscriber);
            }
        }
    }

    private void handleScreenSaving(LinkedHashMap settings) {
        Integer screenSavePeriodMins = (Integer) settings.get("screenSavePeriodMins");
        if (screenSavePeriodMins != null) {
            screenSaveLengthMins = (Integer) settings.get("screenSaveLengthMins");
            screenSaverWebPages = (ArrayList<String>) settings.get("screensavers");
            screenSaverObservable = Observable.timer(screenSavePeriodMins, TimeUnit.MINUTES).observeOn(AndroidSchedulers.mainThread());
            screenSaverObservable.subscribe(getScreenSaverSubscriber());
        }
    }

    private void setTimers(LinkedHashMap settings) {
        Object reloadPeriodMinsObject = settings.get("reloadPeriodMins");
        if (reloadPeriodMinsObject != null)
            reloadPeriodMins = (int) reloadPeriodMinsObject;

        Object errorReloadMinsObject = settings.get("errorReloadMins");
        if (errorReloadMinsObject != null)
            errorReloadMins = (int) errorReloadMinsObject;
    }

    /**
     * Setup the WebViews we need.
     *  @param homeUrl
     * @param title
     * @param weight  how much screen estate should this main take?
     */
    private void setupWebView(String homeUrl, String title, float weight) {
        WebView webView = getWebView();
        webViews.add(webView);
        webView.loadUrl(homeUrl);
        addTapToSettings(webView);
        if (reloadPeriodMins > 0) {
            Subscriber s = reloadSubscriber();
            subscribers.add(s);
            Observable.timer(reloadPeriodMins, TimeUnit.MINUTES)
                    .repeat()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(s);
        }

        // A frame layout enables us to overlay the navigation on the web view.
        FrameLayout frameLayout = new FrameLayout(mainActivity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
        frameLayout.setLayoutParams(params);

        // Add navigation options to the web view.
        NavigationLayout navigationLayout = new NavigationLayout(mainActivity, webView, webView.getUrl(), title);
        navigationLayout.setAlpha(0);
        navigationLayouts.add(navigationLayout);

        // Add the web view and our navigation in a frame layout.
        frameLayout.addView(webView);
        frameLayout.addView(navigationLayout);
        mainActivity.addView(frameLayout);
    }

    /**
     * Get subscriber for reloading the webview.
     */
    private Subscriber<WebView> reloadSubscriber() {
        return new Subscriber<WebView>() {
            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {
                Log.e(Constants.TAG, "Error while reloading web view.", e);
            }

            @Override
            public void onNext(WebView webView) {
                webView.reload();
            }
        };
    }

    /**
     * Returns a WebView with a specified weight.
     *
     * @return a WebView with the specified weight.
     */
    private WebView getWebView() {
        final WebView webView = new WebView(mainActivity);
        webView.setWebViewClient(new KioskerWebViewClient(errorReloadMins));
        webView.setWebChromeClient(new KioskerWebChromeClient());
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setWebContentsDebuggingEnabled(true);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setGeolocationDatabasePath("/data/data/Kiosker");
        return webView;
    }


    /**
     * Add our secret taps for opening settings to a WebView.
     */
    private void addTapToSettings(WebView webView) {
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            // Create a click listener that will open settings on the correct number of taps.
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mainActivity.stopScheduledTasks();

                    Date now = new Date();

                    // If we detect a double tap count down the taps needed to trigger settings.
                    if (now.getTime() - lastTap.getTime() < 300) {
                        if (taps == 2) {
                            taps = tapsToOpenSettings;
                            mainActivity.showingSettings = true;
                            Intent i = new Intent(mainActivity, SettingsActivity.class);
                            i.putExtra(Constants.KIOSKER_DEVICE_ID, Constants.getDeviceId(mainActivity));
                            i.putExtra(Constants.JSON_BASE_URL, Constants.getJsonBaseUrl(mainActivity));
                            i.putExtra(Constants.KIOSKER_ALLOW_HOME_ID, Constants.getAllowHome(mainActivity));
                            mainActivity.startActivityForResult(i, 0);
                        }
                        taps--;
                    }
                    // If it is not a double tap reset the taps counter.
                    else
                        taps = tapsToOpenSettings;

                    lastTap = now;
                }
                // When the user lifts his finger, restart all scheduled tasks.
                else if (event.getAction() == MotionEvent.ACTION_UP)
                    mainActivity.startScheduledTasks();

                    // When the user moves his finger on the view show our navigation.
                else if (event.getAction() == MotionEvent.ACTION_MOVE)
                    navigationLayouts.get(webViews.indexOf(v)).showNavigation();
                return false;
            }
        });
    }

    public void clearWebViews() {
        for (WebView webView : webViews) {
            webView.destroy();
        }
        for (NavigationLayout navigationLayout : navigationLayouts) {
            navigationLayout.removeAllViews();
        }
        navigationLayouts.clear();
        webViews.clear();
        homeWebPages.clear();
        sitesWebPages.clear();
    }

    /**
     * Reloads all the web views.
     * The main view will load the default page.
     * Other web views will reload their current page.
     */
    public void reloadWebViews() {
        Observable.from(1).observeOn(AndroidSchedulers.mainThread()).subscribe(new Action1<Integer>() {
            @Override
            public void call(Integer integer) {
                if (webViews != null && !webViews.isEmpty() && homeWebPages != null && !homeWebPages.isEmpty())
                    for (int i = 0; i < webViews.size(); i++) {
                        if (i == 0)
                            webViews.get(i).loadUrl(homeWebPages.get(0));
                        else
                            webViews.get(i).reload();
                    }
            }
        });
    }

    private float layoutTranslator(int layout, boolean mainWebPage) {
        switch (layout) {
            case 1:
                return 0.5f;
            case 2:
                if (mainWebPage)
                    return 0.6f;
                else
                    return 0.4f;
            case 3:
                if (mainWebPage)
                    return 0.7f;
                else
                    return 0.3f;
            case 4:
                if (mainWebPage)
                    return 0.8f;
                else
                    return 0.2f;
            default: // Our default is the fullscreen layout
                if (mainWebPage)
                    return 1.0f;
                else
                    return 0f;
        }
    }

    public void startScreenSaverSubscription() {
        // Restart the idle time out if we are not in the standby period.
        if (screenSaverObservable != null && !mainActivity.currentlyInStandbyPeriod)
            screenSaverObservable.subscribe(getScreenSaverSubscriber());
    }

    public void stopScreenSaverSubscription() {
        mainActivity.currentlyScreenSaving = false;
        screenSaverSubscriber.unsubscribe();
        if (wasScreenSaving) {
            wasScreenSaving = false;
            mainActivity.refreshDevice();
        }
    }

    public Subscriber<Long> getScreenSaverSubscriber() {
        screenSaverSubscriber = new Subscriber<Long>() {
            @Override
            public void onCompleted() {
                Observable.timer(screenSaveLengthMins, TimeUnit.MINUTES)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Action1<Long>() {
                            @Override
                            public void call(Long aLong) {
                                // Here we are finished screen saving and we return to the normal layout.
                                mainActivity.currentlyScreenSaving = false;
                                mainActivity.refreshDevice();
                            }
                        });
            }

            @Override
            public void onError(Throwable e) {
                Log.e(Constants.TAG, "Error while screen saving.", e);
            }

            @Override
            public void onNext(Long l) {
                if (!screenSaverWebPages.isEmpty()) {
                    mainActivity.currentlyScreenSaving = true;
                    wasScreenSaving = true;

                    Random rnd = new Random();
                    int randomIndex = rnd.nextInt(screenSaverWebPages.size() / 2) * 2;

                    // Clean current view .
                    mainActivity.cleanUpMainView();

                    // Make a new full screen web view with a random url from the screen saver urls.
                    setupWebView(screenSaverWebPages.get(randomIndex), screenSaverWebPages.get(randomIndex + 1), 1.0f);
                } else
                    unsubscribe();
            }
        };
        return screenSaverSubscriber;
    }
}