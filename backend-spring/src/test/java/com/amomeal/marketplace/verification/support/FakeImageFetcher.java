package com.amomeal.marketplace.verification.support;

import com.amomeal.marketplace.verification.gemini.ImageFetcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Returns the URL bytes as the "image"; never touches the network. */
public class FakeImageFetcher implements ImageFetcher {

    private final List<String> fetched = new ArrayList<>();
    private volatile boolean failing;
    private volatile int failFromCall = Integer.MAX_VALUE;

    public synchronized void reset() {
        fetched.clear();
        failing = false;
        failFromCall = Integer.MAX_VALUE;
    }

    /** Fetches numbered from n (1-based) onward fail. */
    public void failFromCall(int n) {
        this.failFromCall = n;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public synchronized List<String> fetched() {
        return List.copyOf(fetched);
    }

    @Override
    public FetchedImage fetch(String url) {
        int count;
        synchronized (this) {
            fetched.add(url);
            count = fetched.size();
        }
        if (failing || count >= failFromCall) {
            throw new IllegalStateException("fetch failed: " + url);
        }
        return new FetchedImage(url.getBytes(StandardCharsets.UTF_8), "image/jpeg");
    }
}
