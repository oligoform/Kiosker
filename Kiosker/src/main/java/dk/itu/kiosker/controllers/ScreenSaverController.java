package dk.itu.kiosker.controllers;

import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import dk.itu.kiosker.activities.KioskerActivity;
import dk.itu.kiosker.models.Constants;
import dk.itu.kiosker.utils.CustomerErrorLogger;
import dk.itu.kiosker.utils.SettingsExtractor;
import dk.itu.kiosker.web.WebPage;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;

public class ScreenSaverController {
    private final KioskerActivity kioskerActivity;
    private final ArrayList<Subscriber> subscribers;
    private final WebController webController;
    private int screenSaveLengthMins;
    private ArrayList<WebPage> screenSaverWebPages;
    private Observable<Long> screenSaverObservable;
    private Subscriber<Long> screenSaverSubscriber;

    public ScreenSaverController(KioskerActivity kioskerActivity, ArrayList<Subscriber> subscribers, WebController webController) {
        this.kioskerActivity = kioskerActivity;
        this.subscribers = subscribers;
        this.webController = webController;
    }

    protected void handleScreenSaving(LinkedHashMap settings) {
        int screenSavePeriodMins = SettingsExtractor.getInteger(settings, "screenSavePeriodMins");
        if (screenSavePeriodMins > 0) {
            screenSaveLengthMins = SettingsExtractor.getInteger(settings, "screenSaveLengthMins");
            screenSaverWebPages = SettingsExtractor.getWebPages(settings, "screensavers");
            if (screenSaveLengthMins > 0) {
                screenSaverObservable = Observable.timer(screenSavePeriodMins, TimeUnit.MINUTES).observeOn(AndroidSchedulers.mainThread());
                startScreenSaverSubscription();
            }
        }
    }

    public void startScreenSaverSubscription() {
        // Restart the idle time out if we are not in the standby period.
        if (screenSaverObservable != null && !kioskerActivity.currentlyInStandbyPeriod)
            screenSaverObservable.subscribe(getScreenSaverSubscriber());
    }

    public void stopScreenSaverSubscription() {
        if (screenSaverSubscriber != null) {
            screenSaverSubscriber.unsubscribe();
            subscribers.remove(screenSaverSubscriber);
        }
        if (kioskerActivity.currentlyScreenSaving) {
            kioskerActivity.currentlyScreenSaving = false;
            kioskerActivity.refreshDevice();
        }
    }

    Subscriber<Long> getScreenSaverSubscriber() {
        if (screenSaverSubscriber != null && !screenSaverSubscriber.isUnsubscribed())
            screenSaverSubscriber.unsubscribe();
        screenSaverSubscriber = new Subscriber<Long>() {
            @Override
            public void onCompleted() {
                subscribers.remove(screenSaverSubscriber);
                Observable.timer(screenSaveLengthMins, TimeUnit.MINUTES)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Action1<Long>() {
                            @Override
                            public void call(Long aLong) {
                                Log.d(Constants.TAG, "Stopping screensaver.");
                                // Here we are finished screen saving and we return to the normal layout.
                                kioskerActivity.currentlyScreenSaving = false;

                                // Return to the previous brightness level
                                StandbyController.dimDevice(kioskerActivity);

                                kioskerActivity.refreshDevice();
                            }
                        });
            }

            @Override
            public void onError(Throwable e) {
                CustomerErrorLogger.log("Error while screen saving.", e, kioskerActivity);
            }

            @Override
            public void onNext(Long l) {
                if (!screenSaverWebPages.isEmpty() && !kioskerActivity.currentlyInStandbyPeriod) {
                    kioskerActivity.currentlyScreenSaving = true;

                    Random rnd = new Random();
                    int randomIndex = rnd.nextInt(screenSaverWebPages.size());

                    // Clean current view .
                    kioskerActivity.cleanUpMainView();

                    // Make a new full screen web view with a random url from the screen saver urls.
                    webController.setupWebView(false, screenSaverWebPages.get(randomIndex), 1.0f, false, false);

                    // Run the screen saver at max brightness
                    StandbyController.unDimDevice(kioskerActivity);

                    Log.d(Constants.TAG, String.format("Starting screensaver %s.", screenSaverWebPages.get(randomIndex).title));
                } else {
                    unsubscribe();
                    subscribers.remove(this);
                }
            }
        };

        // Add to subscribers so we can cancel this later
        subscribers.add(screenSaverSubscriber);
        return screenSaverSubscriber;
    }
}
