package org.commonhaus.automation.github.discovery;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.RawEvent;
import io.quarkus.runtime.StartupEvent;

@Singleton
class EventCounter {
    static final AtomicInteger countStartupEvent = new AtomicInteger(0);
    static final AtomicInteger countGHInstallationEvent = new AtomicInteger(0);
    static final AtomicInteger countGHInstallationRepoEvent = new AtomicInteger(0);
    static final List<RepositoryDiscoveryEvent> collectRepositoryEvent = new CopyOnWriteArrayList<>();
    static final List<InstallationDiscoveryEvent> collectInstallationEvent = new CopyOnWriteArrayList<>();
    static final List<BootstrapDiscoveryEvent> collectBootstrapEvent = new CopyOnWriteArrayList<>();

    void startupEvent(@Observes StartupEvent ev) {
        countStartupEvent.incrementAndGet();
        System.out.println("EventCounter.startupEvent: " + countStartupEvent.get());
    }

    void onRepositoryDiscoveryEvent(@Observes RepositoryDiscoveryEvent ev) {
        collectRepositoryEvent.add(ev);
        System.out.println("EventCounter.onRepositoryDiscoveryEvent: event=" + ev);
    }

    void onInstallationDiscoveryEvent(@Observes InstallationDiscoveryEvent ev) {
        collectInstallationEvent.add(ev);
        System.out.println("EventCounter.onInstallationDiscoveryEvent: event=" + ev);
    }

    void onBootstrapDiscoveryEvent(@Observes BootstrapDiscoveryEvent ev) {
        collectBootstrapEvent.add(ev);
        System.out.println("EventCounter.onBootstrapDiscoveryEvent: event=" + ev);
    }

    void reset() {
        countStartupEvent.set(0);
        countGHInstallationEvent.set(0);
        countGHInstallationRepoEvent.set(0);
        collectRepositoryEvent.clear();
        collectInstallationEvent.clear();
        collectBootstrapEvent.clear();
    }

    static class CountGHEvents {
        void onInstallationChange(@RawEvent(event = "installation") GitHubEvent gitHubEvent) {
            countGHInstallationEvent.incrementAndGet();
            System.out.println("EventCounter.onInstallationChange: eventAction=" + gitHubEvent.getEventAction()
                    + ", action=" + gitHubEvent.getAction());
        }

        void onInstallationRepositoryChange(@RawEvent(event = "installation_repositories") GitHubEvent gitHubEvent) {
            countGHInstallationRepoEvent.incrementAndGet();
            System.out.println("EventCounter.onInstallationRepositoryChange: eventAction=" + gitHubEvent.getEventAction()
                    + ", action=" + gitHubEvent.getAction());
        }
    }
}
