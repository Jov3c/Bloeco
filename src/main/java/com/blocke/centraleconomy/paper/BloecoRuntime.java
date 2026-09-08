package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.result.Result;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Paper lifecycle owner for the asynchronous economy core. */
public final class BloecoRuntime implements AutoCloseable {
    private final AsyncEconomyFacade facade;

    private BloecoRuntime(AsyncEconomyFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    public static BloecoRuntime sqlite(Path databasePath) {
        return new BloecoRuntime(AsyncEconomyFacade.sqlite(databasePath, Clock.systemUTC()));
    }

    public AsyncEconomyFacade facade() { return facade; }
    public CompletionStage<Result<Void>> readyStage() { return facade.readyStage(); }
    @Override public void close() { facade.close(); }
}
