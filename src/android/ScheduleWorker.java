package org.apache.cordova.health;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.records.BloodGlucoseRecord;
import androidx.health.connect.client.records.HeartRateRecord;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.request.ReadRecordsRequest;
import androidx.health.connect.client.response.ReadRecordsResponse;
import androidx.health.connect.client.time.TimeRangeFilter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kotlin.reflect.KClass;
import kotlin.jvm.JvmClassMappingKt;
import androidx.concurrent.futures.ResolvableFuture;
import com.google.common.util.concurrent.ListenableFuture;

import androidx.health.connect.client.records.metadata.DataOrigin;

import java.util.Collections;
import java.util.Set;
import android.util.Log;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.Unit;

import org.jetbrains.annotations.NotNull;


public class ScheduleWorker extends ListenableWorker {

	private static final String TAG = "ScheduleWorker";

    public ScheduleWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        ResolvableFuture<Result> future = ResolvableFuture.create();

        Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                
				// Récupérer les données d'entrée
                String datatype = getInputData().getString("DATA_TYPE");
                long timeStart = getInputData().getLong("TIME_START", 0L);
                long timeEnd = getInputData().getLong("TIME_END", 0L);
				int limit = getInputData().getInt("DATA_LIMIT", 1000);
				boolean ascending = getInputData().getBoolean("DATA_ASCENDING", false);
				
				Log.d(TAG, "Type de données : " + datatype);
				
                // Créer le client HealthConnect
                HealthConnectClient healthConnectClient = HealthConnectClient.getOrCreate(getApplicationContext());

                // Définir la plage de temps
                Instant startTime = Instant.ofEpochMilli(timeStart);
                Instant endTime = Instant.ofEpochMilli(timeEnd);

                // Vérifier que datatype est valide
                KClass<? extends Record> type;
				
                if ("BloodGlucoseRecord.class".equals(datatype)) {
                    type = JvmClassMappingKt.getKotlinClass(BloodGlucoseRecord.class);
				} else if ("HeartRateRecord.class".equals(datatype)) {
                    type = JvmClassMappingKt.getKotlinClass(HeartRateRecord.class);
				} else {
					Log.e(TAG, "Type de données non reconnu : " + datatype);
                    future.set(Result.failure());
                    return;
                }


                // Initialiser le pageToken
                String pageToken = null;

				// Fonction récursive pour paginer les résultats
                fetchRecordsPage(healthConnectClient, type, startTime, endTime, ascending, limit, pageToken, future);
				
				
            } catch (Exception e) {
                future.set(Result.failure());
            }
        });

        return future;
    }

    private <T extends Record> void fetchRecordsPage(
		HealthConnectClient healthConnectClient,
		KClass<T> type,
		Instant startTime,
		Instant endTime,
		boolean ascending,
		int limit,
		String pageToken,
		ResolvableFuture<Result> future) {

		try {
			// Créer un ensemble vide de DataOrigin
			Set<DataOrigin> dataOrigins = Collections.emptySet();

			// Construire la requête en utilisant le type explicitement
			ReadRecordsRequest<T> request = new ReadRecordsRequest<>(
				type,
				TimeRangeFilter.between(startTime, endTime),
				dataOrigins,
				ascending,
				limit,  // pageSize
				pageToken
			);

			// Créer une Continuation pour gérer le résultat
			healthConnectClient.readRecords(request, new Continuation<ReadRecordsResponse<T>>() {
				@NotNull
				@Override
				public CoroutineContext getContext() {
					return EmptyCoroutineContext.INSTANCE;
				}

				@Override
				public void resumeWith(@NotNull Object result) {
					try {
						if (result instanceof com.google.common.util.concurrent.ListenableFuture) {
							com.google.common.util.concurrent.ListenableFuture<ReadRecordsResponse<T>> responseFuture =
								(com.google.common.util.concurrent.ListenableFuture<ReadRecordsResponse<T>>) result;

							Futures.addCallback(responseFuture, new FutureCallback<ReadRecordsResponse<T>>() {
								@Override
								public void onSuccess(ReadRecordsResponse<T> response) {
									List<T> records = response.getRecords();
									
									
									
									
									String nextPageToken = response.getPageToken();

									if (nextPageToken == null) {
										future.set(Result.success());
									} else {
										fetchRecordsPage(
											healthConnectClient,
											type,
											startTime,
											endTime,
											ascending,
											limit,
											nextPageToken,
											future
										);
									}
								}

								@Override
				public void onFailure(Throwable t) {
					future.set(Result.failure());
				}
			}, MoreExecutors.directExecutor());
						} else {
							future.set(Result.failure());
						}
					} catch (Exception e) {
						future.set(Result.failure());
					}
				}
			});

		} catch (Exception e) {
			future.set(Result.failure());
		}
	}
}
