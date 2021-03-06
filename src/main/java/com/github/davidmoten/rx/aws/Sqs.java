package com.github.davidmoten.rx.aws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.github.davidmoten.rx.Functions;
import com.github.davidmoten.rx.aws.SqsMessage.Service;
import com.github.davidmoten.util.Preconditions;

import rx.Observable;
import rx.Observer;
import rx.Scheduler;
import rx.exceptions.CompositeException;
import rx.functions.Func0;
import rx.schedulers.Schedulers;

public final class Sqs {

    private Sqs() {
        // prevent instantiation
    }

    public static String sendToQueueUsingS3(AmazonSQSClient sqs, String queueUrl, AmazonS3Client s3,
            String bucketName, Map<String, String> headers, byte[] message,
            Func0<String> s3IdFactory) {
        Preconditions.checkNotNull(sqs);
        Preconditions.checkNotNull(s3);
        Preconditions.checkNotNull(queueUrl);
        Preconditions.checkNotNull(bucketName);
        Preconditions.checkNotNull(message);
        String s3Id = s3IdFactory.call();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(message.length);
        for (Entry<String, String> header : headers.entrySet()) {
            metadata.setHeader(header.getKey(), header.getValue());
        }
        s3.putObject(bucketName, s3Id, new ByteArrayInputStream(message), metadata);
        try {
            sqs.sendMessage(queueUrl, s3Id);
        } catch (RuntimeException e) {
            try {
                s3.deleteObject(bucketName, s3Id);
                throw e;
            } catch (RuntimeException e2) {
                throw new CompositeException(e, e2);
            }
        }
        return s3Id;
    }

    public static String sendToQueueUsingS3(AmazonSQSClient sqs, String queueUrl, AmazonS3Client s3,
            String bucketName, byte[] message, Func0<String> s3IdFactory) {
        return sendToQueueUsingS3(sqs, queueUrl, s3, bucketName, Collections.emptyMap(), message,
                s3IdFactory);
    }

    public static String sendToQueueUsingS3(AmazonSQSClient sqs, String queueUrl, AmazonS3Client s3,
            String bucketName, byte[] message) {
        return sendToQueueUsingS3(sqs, queueUrl, s3, bucketName, message,
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    public static String sendToQueueUsingS3(AmazonSQSClient sqs, String queueUrl, AmazonS3Client s3,
            String bucketName, Map<String, String> headers, byte[] message) {
        return sendToQueueUsingS3(sqs, queueUrl, s3, bucketName, headers, message,
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    public static final class SqsBuilder {
        private final String queueName;
        private Func0<AmazonSQSClient> sqs = null;
        private Optional<Func0<AmazonS3Client>> s3 = Optional.empty();
        private Optional<String> bucketName = Optional.empty();
        private Optional<Observable<Integer>> waitTimesSeconds = Optional.empty();

        SqsBuilder(String queueName) {
            Preconditions.checkNotNull(queueName);
            this.queueName = queueName;
        }

        public ViaS3Builder bucketName(String bucketName) {
            this.bucketName = Optional.of(bucketName);
            return new ViaS3Builder(this);
        }

        public SqsBuilder sqsFactory(Func0<AmazonSQSClient> sqsFactory) {
            this.sqs = sqsFactory;
            return this;
        }

        public SqsBuilder waitTimes(Observable<? extends Number> waitTimesSeconds, TimeUnit unit) {
            this.waitTimesSeconds = Optional.of(
                    waitTimesSeconds.map(x -> (int) unit.toSeconds(Math.round(x.doubleValue()))));
            return this;
        }

        public SqsBuilder interval(int interval, TimeUnit unit, Scheduler scheduler) {
            return waitTimes( //
                    Observable.just(0) //
                            .concatWith(Observable.interval(interval, unit, scheduler).map(x -> 0)),
                    TimeUnit.SECONDS);
        }

        public SqsBuilder interval(int interval, TimeUnit unit) {
            return interval(interval, unit, Schedulers.io());
        }

        public Observable<SqsMessage> messages() {
            return Sqs.messages(sqs, s3, queueName, bucketName, waitTimesSeconds);
        }

    }

    public static final class ViaS3Builder {

        private final SqsBuilder sqsBuilder;

        public ViaS3Builder(SqsBuilder sqsBuilder) {
            this.sqsBuilder = sqsBuilder;
        }

        public SqsBuilder s3Factory(Func0<AmazonS3Client> s3Factory) {
            sqsBuilder.s3 = Optional.of(s3Factory);
            return sqsBuilder;
        }

    }

    public static SqsBuilder queueName(String queueName) {
        return new SqsBuilder(queueName);
    }

    static Observable<SqsMessage> messages(Func0<AmazonSQSClient> sqsFactory,
            Optional<Func0<AmazonS3Client>> s3Factory, String queueName,
            Optional<String> bucketName, Optional<Observable<Integer>> waitTimesSeconds) {
        Preconditions.checkNotNull(sqsFactory);
        Preconditions.checkNotNull(s3Factory);
        Preconditions.checkNotNull(queueName);
        Preconditions.checkNotNull(bucketName);
        Preconditions.checkNotNull(waitTimesSeconds);
        return Observable.using(sqsFactory, sqs -> createObservableWithSqs(sqs, s3Factory,
                sqsFactory, queueName, bucketName, waitTimesSeconds), sqs -> sqs.shutdown());
    }

    private static Observable<SqsMessage> createObservableWithSqs(AmazonSQSClient sqs,
            Optional<Func0<AmazonS3Client>> s3Factory, Func0<AmazonSQSClient> sqsFactory,
            String queueName, Optional<String> bucketName,
            Optional<Observable<Integer>> waitTimesSeconds) {

        return Observable
                .using(() -> s3Factory.map(Func0::call), //
                        s3 -> createObservableWithS3(sqs, s3Factory, sqsFactory, queueName,
                                bucketName, s3, waitTimesSeconds),
                        s3 -> s3.ifPresent(AmazonS3Client::shutdown));
    }

    private static Observable<SqsMessage> createObservableWithS3(AmazonSQSClient sqs,
            Optional<Func0<AmazonS3Client>> s3Factory, Func0<AmazonSQSClient> sqsFactory,
            String queueName, Optional<String> bucketName, Optional<AmazonS3Client> s3,
            Optional<Observable<Integer>> waitTimesSeconds) {
        final Service service = new Service(s3Factory, sqsFactory, s3, sqs, queueName, bucketName);
        if (waitTimesSeconds.isPresent()) {
            return createObservablePolling(sqs, s3Factory, sqsFactory, queueName, bucketName, s3,
                    waitTimesSeconds.get());
        } else {
            return createObservableContinousLongPolling(sqs, queueName, bucketName, s3, service);
        }
    }

    private static Observable<SqsMessage> createObservablePolling(AmazonSQSClient sqs,
            Optional<Func0<AmazonS3Client>> s3Factory, Func0<AmazonSQSClient> sqsFactory,
            String queueName, Optional<String> bucketName, Optional<AmazonS3Client> s3,
            Observable<Integer> waitTimesSeconds) {
        final Service service = new Service(s3Factory, sqsFactory, s3, sqs, queueName, bucketName);
        return waitTimesSeconds.flatMap(n -> get(sqs, queueName, bucketName, s3, service, n), 1);
    }

    private static Observable<SqsMessage> get(AmazonSQSClient sqs, String queueName,
            Optional<String> bucketName, Optional<AmazonS3Client> s3, Service service,
            int waitTimeSeconds) {
        return Observable.defer(() -> {
            String queueUrl = sqs.getQueueUrl(queueName).getQueueUrl();
            return Observable
                    .just(sqs.receiveMessage(request(queueName, waitTimeSeconds)) //
                            .getMessages() //
                            .stream() //
                            .map(m -> Sqs.getNextMessage(m, queueUrl, bucketName, s3, sqs, service)) //
                            .collect(Collectors.toList())) //
                    .concatWith(Observable
                            .defer(() -> Observable.just(sqs.receiveMessage(request(queueName, 0)) //
                                    .getMessages() //
                                    .stream() //
                                    .map(m -> Sqs.getNextMessage(m, queueUrl, bucketName, s3, sqs,
                                            service)) //
                            .collect(Collectors.toList()))) //
                            .repeat())
                    .takeWhile(list -> !list.isEmpty()) //
                    .flatMapIterable(Functions.identity()) //
                    .filter(opt -> opt.isPresent()).map(opt -> opt.get());
        });//
    }

    private static Observable<SqsMessage> createObservableContinousLongPolling(AmazonSQSClient sqs,
            String queueName, Optional<String> bucketName, Optional<AmazonS3Client> s3,
            final Service service) {
        return Observable.create(
                new ContinuousLongPollingSyncOnSubscribe(sqs, queueName, s3, bucketName, service));
    }

    private static final class ContinuousLongPollingSyncOnSubscribe
            extends rx.observables.SyncOnSubscribe<State, SqsMessage> {

        private final AmazonSQSClient sqs;
        private final String queueName;
        private final Optional<AmazonS3Client> s3;
        private final Optional<String> bucketName;
        private final Service service;

        private ReceiveMessageRequest request;
        private String queueUrl;

        public ContinuousLongPollingSyncOnSubscribe(AmazonSQSClient sqs, String queueName,
                Optional<AmazonS3Client> s3, Optional<String> bucketName, Service service) {
            this.sqs = sqs;
            this.queueName = queueName;
            this.s3 = s3;
            this.bucketName = bucketName;
            this.service = service;
        }

        @Override
        protected State generateState() {
            queueUrl = sqs.getQueueUrl(queueName).getQueueUrl();
            request = new ReceiveMessageRequest(queueUrl) //
                    .withWaitTimeSeconds(20) //
                    .withMaxNumberOfMessages(10);
            return new State(new LinkedList<>());
        }

        @Override
        protected State next(State state, Observer<? super SqsMessage> observer) {
            final Queue<Message> q = state.queue;
            Optional<SqsMessage> next = Optional.empty();
            while (!next.isPresent()) {
                while (q.isEmpty()) {
                    ReceiveMessageResult result = sqs.receiveMessage(request);
                    q.addAll(result.getMessages());
                }
                Message message = q.poll();
                next = getNextMessage(message, queueUrl, bucketName, s3, sqs, service);
            }
            observer.onNext(next.get());
            return state;
        }

    }

    static Optional<SqsMessage> getNextMessage(Message message, String queueUrl,
            Optional<String> bucketName, Optional<AmazonS3Client> s3, AmazonSQSClient sqs,
            Service service) {
        if (bucketName.isPresent()) {
            String s3Id = message.getBody();
            if (!s3.get().doesObjectExist(bucketName.get(), s3Id)) {
                sqs.deleteMessage(queueUrl, message.getReceiptHandle());
                return Optional.empty();
            } else {
                S3Object object = s3.get().getObject(bucketName.get(), s3Id);
                byte[] content = readAndClose(object.getObjectContent());
                long timestamp = object.getObjectMetadata().getLastModified().getTime();
                SqsMessage mb = new SqsMessage(message.getReceiptHandle(), content, timestamp,
                        Optional.of(s3Id), service);
                return Optional.of(mb);
            }
        } else {
            SqsMessage mb = new SqsMessage(message.getReceiptHandle(),
                    message.getBody().getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(),
                    Optional.empty(), service);
            return Optional.of(mb);
        }
    }

    private static final class State {

        final Queue<Message> queue;

        public State(Queue<Message> queue) {
            this.queue = queue;
        }

    }

    private static ReceiveMessageRequest request(String queueUrl, int waitTimeSeconds) {
        return new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(20)
                .withWaitTimeSeconds(waitTimeSeconds);
    }

    // Visible for testing
    static byte[] readAndClose(InputStream is) {
        Preconditions.checkNotNull(is);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int n;
            while ((n = is.read(bytes)) != -1) {
                bos.write(bytes, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
