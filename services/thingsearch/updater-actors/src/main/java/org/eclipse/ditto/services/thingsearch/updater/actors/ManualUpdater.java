/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.thingsearch.updater.actors;

import java.time.Duration;
import java.util.Optional;

import org.bson.Document;
import org.eclipse.ditto.services.models.things.ThingTag;
import org.eclipse.ditto.services.utils.akka.LogUtil;

import com.mongodb.reactivestreams.client.MongoDatabase;

import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.DiagnosticLoggingAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.DelayOverflowStrategy;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.RestartSource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

/**
 * Cluster singleton to trigger index updates from a collection.
 */
public final class ManualUpdater extends AbstractActor {

    /**
     * Name of this actor.
     */
    public static final String ACTOR_NAME = "manualUpdater";

    /**
     * Name of the collection to trigger updates.
     */
    public static final String COLLECTION_NAME = "searchThingsManualUpdates";

    /**
     * Field containing ID of the thing in the collection to trigger updates.
     */
    public static final String ID_FIELD = "id";

    /**
     * Field containing revision of the thing in the collection to trigger updates.
     */
    public static final String REVISION = "revision";

    private static final Duration DELAY_PER_ELEMENT = Duration.ofSeconds(1L);

    private static final Duration DELAY_PER_CURSOR = Duration.ofMinutes(1L);

    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1L);

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1L);

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private ManualUpdater(final MongoDatabase database, final ActorRef thingsUpdater) {
        this(database, thingsUpdater, DELAY_PER_ELEMENT, DELAY_PER_CURSOR, MIN_BACKOFF, MAX_BACKOFF);
    }

    ManualUpdater(final MongoDatabase database, final ActorRef thingsUpdater, final Duration delayPerElement,
            final Duration delayPerCursor, final Duration minBackoff, final Duration maxBackoff) {

        final Source<ThingTag, NotUsed> restartSource =
                RestartSource.onFailuresWithBackoff(minBackoff, maxBackoff, 1.0,
                        () -> retrieveAllThingTagsInCollection(database, delayPerElement, delayPerCursor));

        final Sink<ThingTag, ?> sink =
                Sink.foreach(thingTag -> thingsUpdater.tell(thingTag, ActorRef.noSender()));

        // run stream in this actor's context so that they stop on this actor's termination
        restartSource.to(sink).run(ActorMaterializer.create(getContext()));
    }

    /**
     * Create Props object for this actor.
     *
     * @param db Mongo database in which to find IDs of things to update.
     * @param thingsUpdater target of messages from this actor.
     * @return Props for this actor.
     */
    public static Props props(final MongoDatabase db, final ActorRef thingsUpdater) {

        return Props.create(ManualUpdater.class, () -> new ManualUpdater(db, thingsUpdater));
    }

    @Override
    public Receive createReceive() {
        return emptyBehavior();
    }

    private Source<ThingTag, NotUsed> retrieveAllThingTagsInCollection(final MongoDatabase db,
            final Duration delayPerElement, final Duration delayPerCursor) {

        return Source.repeat(NotUsed.getInstance())
                .buffer(1, OverflowStrategy.backpressure())
                .delay(delayPerElement, DelayOverflowStrategy.backpressure())
                .flatMapConcat(notUsed -> retrieveThingTagOrElseDelay(db, delayPerCursor));
    }

    private Source<ThingTag, NotUsed> retrieveThingTagOrElseDelay(final MongoDatabase db,
            final Duration delayPerCursor) {

        return Source.fromPublisher(db.getCollection(COLLECTION_NAME).findOneAndDelete(new Document()))
                .buffer(1, OverflowStrategy.backpressure())
                .map(ManualUpdater::convertToThingTag)
                .orElse(Source.single(Optional.<ThingTag>empty()).initialDelay(delayPerCursor))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .log("ManualUpdater", log);
    }

    private static Optional<ThingTag> convertToThingTag(final Document document) {
        try {
            final String id = document.getString(ID_FIELD);
            final long revision = document.getLong(REVISION);
            return Optional.of(ThingTag.of(id, revision));
        } catch (final ClassCastException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
