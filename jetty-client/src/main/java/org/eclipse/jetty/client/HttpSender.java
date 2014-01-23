//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpSender} abstracts the algorithm to send HTTP requests, so that subclasses only implement
 * the transport-specific code to send requests over the wire, implementing
 * {@link #sendHeaders(HttpExchange, HttpContent, Callback)} and
 * {@link #sendContent(HttpExchange, HttpContent, Callback)}.
 * <p />
 * {@link HttpSender} governs two state machines.
 * <p />
 * The request state machine is updated by {@link HttpSender} as the various steps of sending a request
 * are executed, see {@link RequestState}.
 * At any point in time, a user thread may abort the request, which may (if the request has not been
 * completely sent yet) move the request state machine to {@link RequestState#FAILURE}.
 * The request state machine guarantees that the request steps are executed (by I/O threads) only if
 * the request has not been failed already.
 * <p />
 * The sender state machine is updated by {@link HttpSender} from three sources: deferred content notifications
 * (via {@link #onContent()}), 100-continue notifications (via {@link #proceed(HttpExchange, Throwable)})
 * and normal request send (via {@link #sendContent(HttpExchange, HttpContent, Callback)}).
 * This state machine must guarantee that the request sending is never executed concurrently: only one of
 * those sources may trigger the call to {@link #sendContent(HttpExchange, HttpContent, Callback)}.
 *
 * @see HttpReceiver
 */
public abstract class HttpSender implements AsyncContentProvider.Listener
{
    protected static final Logger LOG = Log.getLogger(HttpSender.class);

    private final AtomicReference<RequestState> requestState = new AtomicReference<>(RequestState.QUEUED);
    private final AtomicReference<SenderState> senderState = new AtomicReference<>(SenderState.IDLE);
    private final Callback commitCallback = new CommitCallback();
    private final Callback contentCallback = new ContentCallback();
    private final Callback lastCallback = new LastContentCallback();
    private final HttpChannel channel;
    private volatile HttpContent content;

    protected HttpSender(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected HttpChannel getHttpChannel()
    {
        return channel;
    }

    protected HttpExchange getHttpExchange()
    {
        return channel.getHttpExchange();
    }

    @Override
    public void onContent()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        while (true)
        {
            SenderState current = senderState.get();
            switch (current)
            {
                case IDLE:
                {
                    SenderState newSenderState = SenderState.SENDING;
                    if (updateSenderState(current, newSenderState))
                    {
                        LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        // TODO should just call contentCallback.iterate() here.
                        HttpContent content = this.content;
                        if (content.advance())
                            sendContent(exchange, content, contentCallback); // TODO old style usage!
                        else if (content.isConsumed())
                            sendContent(exchange, content, lastCallback);
                        else
                            throw new IllegalStateException();
                        return;
                    }
                    break;
                }
                case SENDING:
                {
                    SenderState newSenderState = SenderState.SENDING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case EXPECTING:
                {
                    SenderState newSenderState = SenderState.EXPECTING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case PROCEEDING:
                {
                    SenderState newSenderState = SenderState.PROCEEDING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case SENDING_WITH_CONTENT:
                case EXPECTING_WITH_CONTENT:
                case PROCEEDING_WITH_CONTENT:
                case WAITING:
                {
                    LOG.debug("Deferred content available, {}", current);
                    return;
                }
                default:
                {
                    throw new IllegalStateException(current.toString());
                }
            }
        }
    }

    public void send(HttpExchange exchange)
    {
        Request request = exchange.getRequest();
        Throwable cause = request.getAbortCause();
        if (cause != null)
        {
            exchange.abort(cause);
        }
        else
        {
            if (!queuedToBegin(request))
                throw new IllegalStateException();

            ContentProvider contentProvider = request.getContent();
            HttpContent content = this.content = new HttpContent(contentProvider);

            SenderState newSenderState = SenderState.SENDING;
            if (expects100Continue(request))
                newSenderState = content.hasContent() ? SenderState.EXPECTING_WITH_CONTENT : SenderState.EXPECTING;
            if (!updateSenderState(SenderState.IDLE, newSenderState))
                throw new IllegalStateException();

            // Setting the listener may trigger calls to onContent() by other
            // threads so we must set it only after the sender state has been updated
            if (contentProvider instanceof AsyncContentProvider)
                ((AsyncContentProvider)contentProvider).setListener(this);

            if (!beginToHeaders(request))
                return;

            sendHeaders(exchange, content, commitCallback);
        }
    }

    protected boolean expects100Continue(Request request)
    {
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    protected boolean queuedToBegin(Request request)
    {
        if (!updateRequestState(RequestState.QUEUED, RequestState.BEGIN))
            return false;
        LOG.debug("Request begin {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyBegin(request);
        return true;
    }

    protected boolean beginToHeaders(Request request)
    {
        if (!updateRequestState(RequestState.BEGIN, RequestState.HEADERS))
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Request headers {}{}{}", request, System.getProperty("line.separator"), request.getHeaders().toString().trim());
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyHeaders(request);
        return true;
    }

    protected boolean headersToCommit(Request request)
    {
        if (!updateRequestState(RequestState.HEADERS, RequestState.COMMIT))
            return false;
        LOG.debug("Request committed {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyCommit(request);
        return true;
    }

    protected boolean someToContent(Request request, ByteBuffer content)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                if (!updateRequestState(current, RequestState.CONTENT))
                    return false;
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content {}{}{}", request, System.getProperty("line.separator"), BufferUtil.toDetailString(content));
                RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
                notifier.notifyContent(request, content);
                return true;
            }
            case FAILURE:
            {
                return false;
            }
            default:
            {
                throw new IllegalStateException(current.toString());
            }
        }
    }

    protected boolean someToSuccess(HttpExchange exchange)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                // Mark atomically the request as completed, with respect
                // to concurrency between request success and request failure.
                boolean completed = exchange.requestComplete();
                if (!completed)
                    return false;

                // Reset to be ready for another request
                reset();

                // Mark atomically the request as terminated and succeeded,
                // with respect to concurrency between request and response.
                Result result = exchange.terminateRequest(null);

                // It is important to notify completion *after* we reset because
                // the notification may trigger another request/response
                Request request = exchange.getRequest();
                LOG.debug("Request success {}", request);
                HttpDestination destination = getHttpChannel().getHttpDestination();
                destination.getRequestNotifier().notifySuccess(exchange.getRequest());

                if (result != null)
                {
                    boolean ordered = destination.getHttpClient().isStrictEventOrdering();
                    if (!ordered)
                        channel.exchangeTerminated(result);
                    LOG.debug("Request/Response succeded {}", request);
                    HttpConversation conversation = exchange.getConversation();
                    destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
                    if (ordered)
                        channel.exchangeTerminated(result);
                }

                return true;
            }
            case FAILURE:
            {
                return false;
            }
            default:
            {
                throw new IllegalStateException(current.toString());
            }
        }
    }

    protected boolean anyToFailure(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        // Mark atomically the request as completed, with respect
        // to concurrency between request success and request failure.
        boolean completed = exchange.requestComplete();
        if (!completed)
            return false;

        // Dispose to avoid further requests
        RequestState requestState = dispose();

        // Mark atomically the request as terminated and failed,
        // with respect to concurrency between request and response.
        Result result = exchange.terminateRequest(failure);

        Request request = exchange.getRequest();
        LOG.debug("Request failure {} {}", exchange, failure);
        HttpDestination destination = getHttpChannel().getHttpDestination();
        destination.getRequestNotifier().notifyFailure(request, failure);

        boolean notCommitted = isBeforeCommit(requestState);
        if (result == null && notCommitted && request.getAbortCause() == null)
        {
            // Complete the response from here
            if (exchange.responseComplete())
            {
                result = exchange.terminateResponse(failure);
                LOG.debug("Failed response from request {}", exchange);
            }
        }

        if (result != null)
        {
            boolean ordered = destination.getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(result);
            LOG.debug("Request/Response failed {}", request);
            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
            if (ordered)
                channel.exchangeTerminated(result);
        }

        return true;
    }

    /**
     * Implementations should send the HTTP headers over the wire, possibly with some content,
     * in a single write, and notify the given {@code callback} of the result of this operation.
     * <p />
     * If there is more content to send, then {@link #sendContent(HttpExchange, HttpContent, Callback)}
     * will be invoked.
     *
     * @param exchange the exchange to send
     * @param content the content to send
     * @param callback the callback to notify
     */
    protected abstract void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback);

    /**
     * Implementations should send the content at the {@link HttpContent} cursor position over the wire.
     * <p />
     * The {@link HttpContent} cursor is advanced by {@link HttpSender} at the right time, and if more
     * content needs to be sent, this method is invoked again; subclasses need only to send the content
     * at the {@link HttpContent} cursor position.
     * <p />
     * This method is invoked one last time when {@link HttpContent#isConsumed()} is true and therefore
     * there is no actual content to send.
     * This is done to allow subclasses to write "terminal" bytes (such as the terminal chunk when the
     * transfer encoding is chunked) if their protocol needs to.
     *
     * @param exchange the exchange to send
     * @param content the content to send
     * @param callback the callback to notify
     */
    protected abstract void sendContent(HttpExchange exchange, HttpContent content, Callback callback);

    protected void reset()
    {
        content.close();
        content = null;
        requestState.set(RequestState.QUEUED);
        senderState.set(SenderState.IDLE);
    }

    protected RequestState dispose()
    {
        while (true)
        {
            RequestState current = requestState.get();
            if (updateRequestState(current, RequestState.FAILURE))
            {
                HttpContent content = this.content;
                if (content != null)
                    content.close();
                return current;
            }
        }
    }

    public void proceed(HttpExchange exchange, Throwable failure)
    {
        if (!expects100Continue(exchange.getRequest()))
            return;

        if (failure != null)
        {
            anyToFailure(failure);
            return;
        }

        while (true)
        {
            SenderState current = senderState.get();
            switch (current)
            {
                case EXPECTING:
                {
                    // We are still sending the headers, but we already got the 100 Continue.
                    if (updateSenderState(current, SenderState.PROCEEDING))
                    {
                        LOG.debug("Proceeding while expecting");
                        return;
                    }
                    break;
                }
                case EXPECTING_WITH_CONTENT:
                {
                    // More deferred content was submitted to onContent(), we already
                    // got the 100 Continue, but we may be still sending the headers
                    // (for example, with SSL we may have sent the encrypted data,
                    // received the 100 Continue but not yet updated the decrypted
                    // WriteFlusher so sending more content now may result in a
                    // WritePendingException).
                    if (updateSenderState(current, SenderState.PROCEEDING_WITH_CONTENT))
                    {
                        LOG.debug("Proceeding while scheduled");
                        return;
                    }
                    break;
                }
                case WAITING:
                {
                    // We received the 100 Continue, now send the content if any.
                    HttpContent content = this.content;
                    // TODO should just call contentCallback.iterate() here.
                    if (content.advance())
                    {
                        // There is content to send.
                        if (!updateSenderState(current, SenderState.SENDING))
                            throw new IllegalStateException();
                        LOG.debug("Proceeding while waiting");
                        sendContent(exchange, content, contentCallback); // TODO old style usage!
                        return;
                    }
                    else
                    {
                        // No content to send yet - it's deferred.
                        if (!updateSenderState(current, SenderState.IDLE))
                            throw new IllegalStateException();
                        LOG.debug("Proceeding deferred");
                        return;
                    }
                }
                default:
                {
                    throw new IllegalStateException(current.toString());
                }
            }
        }
    }

    public boolean abort(Throwable failure)
    {
        RequestState current = requestState.get();
        boolean abortable = isBeforeCommit(current) || isSending(current);
        return abortable && anyToFailure(failure);
    }

    protected boolean updateRequestState(RequestState from, RequestState to)
    {
        boolean updated = requestState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("RequestState update failed: {} -> {}: {}", from, to, requestState.get());
        return updated;
    }

    private boolean updateSenderState(SenderState from, SenderState to)
    {
        boolean updated = senderState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("SenderState update failed: {} -> {}: {}", from, to, senderState.get());
        return updated;
    }

    private boolean isBeforeCommit(RequestState requestState)
    {
        switch (requestState)
        {
            case QUEUED:
            case BEGIN:
            case HEADERS:
                return true;
            default:
                return false;
        }
    }

    private boolean isSending(RequestState requestState)
    {
        switch (requestState)
        {
            case COMMIT:
            case CONTENT:
                return true;
            default:
                return false;
        }
    }

    /**
     * The request states {@link HttpSender} goes through when sending a request.
     */
    protected enum RequestState
    {
        /**
         * The request is queued, the initial state
         */
        QUEUED,
        /**
         * The request has been dequeued
         */
        BEGIN,
        /**
         * The request headers (and possibly some content) is about to be sent
         */
        HEADERS,
        /**
         * The request headers (and possibly some content) have been sent
         */
        COMMIT,
        /**
         * The request content is being sent
         */
        CONTENT,
        /**
         * The request is failed
         */
        FAILURE
    }

    /**
     * The sender states {@link HttpSender} goes through when sending a request.
     */
    private enum SenderState
    {
        /**
         * {@link HttpSender} is not sending request headers nor request content
         */
        IDLE,
        /**
         * {@link HttpSender} is sending the request header or request content
         */
        SENDING,
        /**
         * {@link HttpSender} is currently sending the request, and deferred content is available to be sent
         */
        SENDING_WITH_CONTENT,
        /**
         * {@link HttpSender} is sending the headers but will wait for 100 Continue before sending the content
         */
        EXPECTING,
        /**
         * {@link HttpSender} is currently sending the headers, will wait for 100 Continue, and deferred content is available to be sent
         */
        EXPECTING_WITH_CONTENT,
        /**
         * {@link HttpSender} has sent the headers and is waiting for 100 Continue
         */
        WAITING,
        /**
         * {@link HttpSender} is sending the headers, while 100 Continue has arrived
         */
        PROCEEDING,
        /**
         * {@link HttpSender} is sending the headers, while 100 Continue has arrived, and deferred content is available to be sent
         */
        PROCEEDING_WITH_CONTENT
    }

    private class CommitCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            try
            {
                process();
            }
            // Catch-all for runtime exceptions
            catch (Exception x)
            {
                anyToFailure(x);
            }
        }

        private void process() throws Exception
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;

            Request request = exchange.getRequest();
            if (!headersToCommit(request))
                return;

            HttpContent content = HttpSender.this.content;

            if (!content.hasContent())
            {
                // No content to send, we are done.
                someToSuccess(exchange);
            }
            else
            {
                // Was any content sent while committing ?
                ByteBuffer contentBuffer = content.getContent();
                if (contentBuffer != null)
                {
                    if (!someToContent(request, contentBuffer))
                        return;
                }

                while (true)
                {
                    SenderState current = senderState.get();
                    switch (current)
                    {
                        case SENDING:
                        {
                            // TODO should just call contentCallback.iterate() here.
                            // We have content to send ?
                            if (content.advance())
                            {
                                sendContent(exchange, content, contentCallback); // TODO old style usage!
                                return;
                            }
                            else
                            {
                                if (content.isConsumed())
                                {
                                    sendContent(exchange, content, lastCallback);
                                    return;
                                }
                                else
                                {
                                    if (updateSenderState(current, SenderState.IDLE))
                                    {
                                        LOG.debug("Waiting for deferred content for {}", request);
                                        return;
                                    }
                                    break;
                                }
                            }
                        }
                        case SENDING_WITH_CONTENT:
                        {
                            // We have deferred content to send.
                            updateSenderState(current, SenderState.SENDING);
                            break;
                        }
                        case EXPECTING:
                        {
                            // We sent the headers, wait for the 100 Continue response.
                            if (updateSenderState(current, SenderState.WAITING))
                                return;
                            break;
                        }
                        case EXPECTING_WITH_CONTENT:
                        {
                            // We sent the headers, we have deferred content to send,
                            // wait for the 100 Continue response.
                            if (updateSenderState(current, SenderState.WAITING))
                                return;
                            break;
                        }
                        case PROCEEDING:
                        {
                            // We sent the headers, we have the 100 Continue response,
                            // we have no content to send.
                            if (updateSenderState(current, SenderState.IDLE))
                                return;
                            break;
                        }
                        case PROCEEDING_WITH_CONTENT:
                        {
                            // We sent the headers, we have the 100 Continue response,
                            // we have deferred content to send.
                            updateSenderState(current, SenderState.SENDING);
                            break;
                        }
                        default:
                        {
                            throw new IllegalStateException();
                        }
                    }
                }
            }
        }

        @Override
        public void failed(Throwable failure)
        {
            anyToFailure(failure);
        }
    }

    private class ContentCallback extends IteratingCallback
    {
        @Override
        protected Action process() throws Exception
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return Action.IDLE;

            Request request = exchange.getRequest();
            HttpContent content = HttpSender.this.content;

            ByteBuffer contentBuffer = content.getContent();
            if (contentBuffer != null)
            {
                if (!someToContent(request, contentBuffer))
                    return Action.IDLE;
            }

            if (content.advance())
            {
                // There is more content to send
                sendContent(exchange, content, this);
                return Action.SCHEDULED;
            }
            

            if (content.isConsumed())
            {
                sendContent(exchange, content, lastCallback);
                return Action.SCHEDULED;
            }

            while (true)
            {
                SenderState current = senderState.get();
                switch (current)
                {
                    case SENDING:
                    {
                        if (updateSenderState(current, SenderState.IDLE))
                        {
                            LOG.debug("Waiting for deferred content for {}", request);
                            return Action.IDLE;
                        }
                        break;
                    }
                    case SENDING_WITH_CONTENT:
                    {
                        if (updateSenderState(current, SenderState.SENDING))
                        {
                            LOG.debug("Deferred content available for {}", request);
                            // TODO: this case is not covered by tests
                            sendContent(exchange, content, this);
                            return Action.SCHEDULED;
                        }
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        @Override
        protected void completed()
        {
            // Nothing to do, since we always return false from process().
            // Termination is obtained via LastContentCallback.
        }

        @Override
        public void failed(Throwable failure)
        {
            super.failed(failure);
            anyToFailure(failure);
        }
    }

    private class LastContentCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;
            someToSuccess(exchange);
        }

        @Override
        public void failed(Throwable failure)
        {
            anyToFailure(failure);
        }
    }
}
