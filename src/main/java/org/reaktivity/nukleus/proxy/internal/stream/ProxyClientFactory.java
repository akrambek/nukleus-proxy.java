/**
 * Copyright 2016-2020 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.proxy.internal.stream;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;
import static org.reaktivity.nukleus.proxy.internal.types.ProxyInfoType.SECURE;

import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.proxy.internal.ProxyConfiguration;
import org.reaktivity.nukleus.proxy.internal.ProxyNukleus;
import org.reaktivity.nukleus.proxy.internal.types.Array32FW;
import org.reaktivity.nukleus.proxy.internal.types.OctetsFW;
import org.reaktivity.nukleus.proxy.internal.types.ProxyAddressFW;
import org.reaktivity.nukleus.proxy.internal.types.ProxyAddressInet6FW;
import org.reaktivity.nukleus.proxy.internal.types.ProxyAddressInetFW;
import org.reaktivity.nukleus.proxy.internal.types.ProxyAddressUnixFW;
import org.reaktivity.nukleus.proxy.internal.types.ProxyInfoFW;
import org.reaktivity.nukleus.proxy.internal.types.ProxySecureInfoFW;
import org.reaktivity.nukleus.proxy.internal.types.codec.ProxyTlvFW;
import org.reaktivity.nukleus.proxy.internal.types.control.RouteFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.ChallengeFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.DataFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.EndFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.FlushFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.ProxyBeginExFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.proxy.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class ProxyClientFactory implements StreamFactory
{
    private static final DirectBuffer HEADER_V2 = new UnsafeBuffer("\r\n\r\n\0\r\nQUIT\n".getBytes(US_ASCII));

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final FlushFW flushRO = new FlushFW();
    private final ChallengeFW challengeRO = new ChallengeFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final FlushFW.Builder flushRW = new FlushFW.Builder();
    private final ChallengeFW.Builder challengeRW = new ChallengeFW.Builder();

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();

    private final ProxyBeginExFW beginExRO = new ProxyBeginExFW();

    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final OctetsFW payloadRO = new OctetsFW();

    private final ProxyInfoFW infoRO = new ProxyInfoFW();
    private final ProxyTlvFW.Builder tlvRW = new ProxyTlvFW.Builder();

    private final ProxyRouter router;
    private final MutableDirectBuffer writeBuffer;
    private final BufferPool encodePool;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;

    private final Long2ObjectHashMap<MessageConsumer> correlations;

    public ProxyClientFactory(
        ProxyConfiguration config,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        ToIntFunction<String> supplyTypeId)
    {
        this.router = new ProxyRouter(router, supplyTypeId.applyAsInt(ProxyNukleus.NAME));
        this.writeBuffer = requireNonNull(writeBuffer);
        this.encodePool = requireNonNull(bufferPool);
        this.supplyInitialId = requireNonNull(supplyInitialId);
        this.supplyReplyId = requireNonNull(supplyReplyId);
        this.correlations = new Long2ObjectHashMap<>();
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer sender)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long streamId = begin.streamId();

        MessageConsumer newStream = null;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            final RouteFW route = router.resolveApp(begin);
            if (route != null)
            {
                final long routeId = begin.routeId();
                final long initialId = begin.streamId();
                final long resolvedId = route.correlationId();

                newStream = new ProxyAppClient(routeId, initialId, sender, resolvedId)::onAppMessage;
            }
        }
        else
        {
            final long replyId = begin.streamId();

            newStream = correlations.remove(replyId);
        }

        return newStream;
    }

    private final class ProxyAppClient
    {
        private final MessageConsumer receiver;
        private final long routeId;
        private final long initialId;
        private final long replyId;

        private final ProxyNetClient net;

        private int initialBudget;
        private int replyBudget;
        private int replyPadding;

        private ProxyAppClient(
            long routeId,
            long initialId,
            MessageConsumer receiver,
            long resolvedId)
        {
            this.routeId = routeId;
            this.initialId = initialId;
            this.receiver = receiver;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.net = new ProxyNetClient(this, resolvedId);
        }

        private void onAppMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onAppBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onAppData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onAppEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onAppAbort(abort);
                break;
            case FlushFW.TYPE_ID:
                final FlushFW flush = flushRO.wrap(buffer, index, index + length);
                onAppFlush(flush);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onAppWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onAppReset(reset);
                break;
            case ChallengeFW.TYPE_ID:
                final ChallengeFW challenge = challengeRO.wrap(buffer, index, index + length);
                onAppChallenge(challenge);
                break;
            default:
                break;
            }
        }

        private void onAppBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();
            final OctetsFW extension = begin.extension();

            final ProxyBeginExFW beginEx = extension.get(beginExRO::tryWrap);

            router.setThrottle(replyId, this::onAppMessage);

            net.doNetBegin(traceId, authorization, affinity, beginEx);
        }

        private void onAppData(
            DataFW data)
        {
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int flags = data.flags();
            final int reserved = data.reserved();
            final OctetsFW payload = data.payload();

            initialBudget -= reserved;

            if (initialBudget < 0)
            {
                doAppReset(traceId, authorization);
                net.doNetAbort(traceId, authorization);
            }
            else
            {
                net.doNetData(traceId, authorization, budgetId, flags, reserved, payload);
            }
        }

        private void onAppEnd(
            EndFW end)
        {
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            net.doNetEnd(traceId, authorization);
        }

        private void onAppAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            net.doNetAbort(traceId, authorization);
        }

        private void onAppFlush(
            FlushFW flush)
        {
            final long traceId = flush.traceId();
            final long authorization = flush.authorization();
            final long budgetId = flush.budgetId();
            final int reserved = flush.reserved();

            net.doNetFlush(traceId, authorization, budgetId, reserved);
        }

        private void onAppWindow(
            WindowFW window)
        {
            final long traceId = window.traceId();
            final long authorization = window.authorization();
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            replyBudget += credit;
            replyPadding = padding;

            net.doNetWindow(traceId, authorization, budgetId, replyBudget, replyPadding);
        }

        private void onAppReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();
            final long authorization = reset.authorization();

            net.doNetReset(traceId, authorization);
        }

        private void onAppChallenge(
            ChallengeFW challenge)
        {
            final long traceId = challenge.traceId();
            final long authorization = challenge.authorization();
            final OctetsFW extension = challenge.extension();

            net.doNetChallenge(traceId, authorization, extension);
        }

        private void doAppBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            doBegin(receiver, routeId, replyId, traceId, authorization, affinity);
        }

        private void doAppData(
            long traceId,
            long authorization,
            int flags,
            long budgetId,
            int reserved,
            OctetsFW payload)
        {
            replyBudget -= reserved;
            assert replyBudget >= 0;

            doData(receiver, routeId, replyId, traceId, authorization, flags, budgetId, reserved, payload);
        }

        private void doAppEnd(
            long traceId,
            long authorization)
        {
            doEnd(receiver, routeId, replyId, traceId, authorization);
        }

        private void doAppAbort(
            long traceId,
            long authorization)
        {
            doAbort(receiver, routeId, replyId, traceId, authorization);
        }

        private void doAppFlush(
            long traceId,
            long authorization,
            long budgetId,
            int reserved)
        {
            doFlush(receiver, routeId, replyId, traceId, authorization, budgetId, reserved);
        }

        private void doAppReset(
            long traceId,
            long authorization)
        {
            doReset(receiver, routeId, initialId, traceId, authorization);
        }

        private void doAppWindow(
            long traceId,
            long authorization,
            long budgetId,
            int maxBudget,
            int minPadding)
        {
            int initialCredit = maxBudget - initialBudget;
            if (initialCredit > 0)
            {
                initialBudget += initialCredit;
                int initialPadding = minPadding;

                doWindow(receiver, routeId, initialId, traceId, authorization, budgetId, initialCredit, initialPadding);
            }
        }

        private void doAppChallenge(
            long traceId,
            long authorization,
            OctetsFW extension)
        {
            doChallenge(receiver, routeId, initialId, traceId, authorization, extension);
        }
    }

    private final class ProxyNetClient
    {
        private final ProxyAppClient app;
        private final long routeId;
        private final long initialId;
        private final long replyId;
        private final MessageConsumer receiver;

        private int encodeSlot = NO_SLOT;
        private int encodeSlotOffset;

        private int initialBudget;
        private int initialPadding;
        private int replyBudget;

        private ProxyNetClient(
            ProxyAppClient application,
            long routeId)
        {
            this.app = application;
            this.routeId = routeId;
            this.initialId = supplyInitialId.applyAsLong(routeId);
            this.replyId =  supplyReplyId.applyAsLong(initialId);
            this.receiver = router.supplyReceiver(initialId);
        }

        private void onNetMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onNetBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onNetData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onNetEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onNetAbort(abort);
                break;
            case FlushFW.TYPE_ID:
                final FlushFW flush = flushRO.wrap(buffer, index, index + length);
                onNetFlush(flush);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onNetWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onNetReset(reset);
                break;
            case ChallengeFW.TYPE_ID:
                final ChallengeFW challenge = challengeRO.wrap(buffer, index, index + length);
                onNetChallenge(challenge);
                break;
            default:
                break;
            }
        }

        private void onNetBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            app.doAppBegin(traceId, authorization, affinity);
        }

        private void onNetData(
            DataFW data)
        {
            final long authorization = data.authorization();
            final long traceId = data.traceId();
            final int flags = data.flags();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();
            final OctetsFW payload = data.payload();

            replyBudget -= reserved;

            if (replyBudget < 0)
            {
                doNetReset(traceId, authorization);
                app.doAppAbort(traceId, authorization);
            }
            else
            {
                app.doAppData(traceId, authorization, flags, budgetId, reserved, payload);
            }
        }

        private void onNetEnd(
            EndFW end)
        {
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            app.doAppEnd(traceId, authorization);
        }

        private void onNetAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            app.doAppAbort(traceId, authorization);
        }

        private void onNetFlush(
            FlushFW flush)
        {
            final long traceId = flush.traceId();
            final long authorization = flush.authorization();
            final long budgetId = flush.budgetId();
            final int reserved = flush.reserved();

            app.doAppFlush(traceId, authorization, budgetId, reserved);
        }

        private void onNetWindow(
            WindowFW window)
        {
            final long traceId = window.traceId();
            final long authorization = window.authorization();
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            initialBudget += credit;
            initialPadding = padding;

            if (encodeSlot != NO_SLOT)
            {
                DirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                OctetsFW payload = payloadRO.wrap(encodeBuffer, 0, encodeSlotOffset);

                doNetData(traceId, authorization, budgetId, 0x03, payload.sizeof() + padding, payload);

                encodePool.release(encodeSlot);
                encodeSlot = NO_SLOT;
            }

            app.doAppWindow(traceId, authorization, budgetId, initialBudget, initialPadding);
        }

        private void onNetReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();
            final long authorization = reset.authorization();

            app.doAppReset(traceId, authorization);
        }

        private void onNetChallenge(
            ChallengeFW challenge)
        {
            final long traceId = challenge.traceId();
            final long authorization = challenge.authorization();
            final OctetsFW extension = challenge.extension();

            app.doAppChallenge(traceId, authorization, extension);
        }

        private void doNetBegin(
            long traceId,
            long authorization,
            long affinity,
            ProxyBeginExFW beginEx)
        {
            assert encodeSlot == NO_SLOT;
            encodeSlot = encodePool.acquire(initialId);
            assert encodeSlot != NO_SLOT;

            MutableDirectBuffer buffer = encodePool.buffer(encodeSlot);
            if (beginEx != null)
            {
                encodeSlotOffset = encodeProxy(buffer, beginEx);
            }
            else
            {
                encodeSlotOffset = encodeLocal(buffer);
            }

            correlations.put(replyId, this::onNetMessage);
            router.setThrottle(initialId, this::onNetMessage);
            doBegin(receiver, routeId, initialId, traceId, authorization, affinity);
        }

        private void doNetData(
            long traceId,
            long authorization,
            long budgetId,
            int flags,
            int reserved,
            OctetsFW payload)
        {
            initialBudget -= reserved;
            assert initialBudget >= 0;

            doData(receiver, routeId, initialId, traceId, authorization, flags, budgetId, reserved, payload);
        }

        private void doNetEnd(
            long traceId,
            long authorization)
        {
            doEnd(receiver, routeId, initialId, traceId, authorization);
        }

        private void doNetAbort(
            long traceId,
            long authorization)
        {
            doAbort(receiver, routeId, initialId, traceId, authorization);
        }

        private void doNetFlush(
            long traceId,
            long authorization,
            long budgetId,
            int reserved)
        {
            doFlush(receiver, routeId, initialId, traceId, authorization, budgetId, reserved);
        }

        private void doNetReset(
            long traceId,
            long authorization)
        {
            correlations.remove(replyId);
            doReset(receiver, routeId, replyId, traceId, authorization);
        }

        private void doNetChallenge(
            long traceId,
            long authorization,
            OctetsFW extension)
        {
            doChallenge(receiver, routeId, replyId, traceId, authorization, extension);
        }

        private void doNetWindow(
            long traceId,
            long authorization,
            long budgetId,
            int maxBudget,
            int minPadding)
        {
            final int replyCredit = maxBudget - replyBudget;
            if (replyCredit > 0)
            {
                replyBudget += replyCredit;
                int replyPadding = minPadding;

                doWindow(receiver, routeId, replyId, traceId, authorization, budgetId, replyCredit, replyPadding);
            }
        }

        private int encodeHeader(
            MutableDirectBuffer buffer)
        {
            buffer.putBytes(0, HEADER_V2, 0, HEADER_V2.capacity());
            return HEADER_V2.capacity();
        }

        private int encodeLocal(
            MutableDirectBuffer buffer)
        {
            int progress = encodeHeader(buffer);

            buffer.putByte(progress++, (byte) 0x20);
            buffer.putByte(progress++, (byte) 0x00);
            buffer.putByte(progress++, (byte) 0x00);
            buffer.putByte(progress++, (byte) 0x00);
            return progress;
        }

        private int encodeProxy(
            MutableDirectBuffer buffer,
            ProxyBeginExFW beginEx)
        {
            ProxyAddressFW address = beginEx.address();
            Array32FW<ProxyInfoFW> infos = beginEx.infos();

            int progress = encodeHeader(buffer);

            buffer.putByte(progress++, (byte) 0x21);

            progress = encodeProxyAddress(buffer, progress, address);
            progress = encodeProxyTlvs(buffer, progress, infos);

            buffer.putShort(14, (short) (progress - 14 - Short.BYTES), BIG_ENDIAN);
            return progress;
        }

        private int encodeProxyAddress(
            MutableDirectBuffer buffer,
            int progress,
            ProxyAddressFW address)
        {
            switch (address.kind())
            {
            case INET:
                progress = encodeProxyAddressInet(buffer, progress, address);
                break;
            case INET6:
                progress = encodeProxyAddressInet6(buffer, progress, address);
                break;
            case UNIX:
                progress = encodeProxyAddressUnix(buffer, progress, address);
                break;
            }
            return progress;
        }

        private int encodeProxyAddressInet(
            MutableDirectBuffer buffer,
            int progress,
            ProxyAddressFW address)
        {
            ProxyAddressInetFW inet = address.inet();
            buffer.putByte(progress++, (byte) (0x10 | (inet.protocol().get().ordinal() + 1)));
            progress += Short.BYTES;
            buffer.putBytes(progress, inet.source().value(), 0, inet.source().sizeof());
            progress += inet.source().sizeof();
            buffer.putBytes(progress, inet.destination().value(), 0, inet.destination().sizeof());
            progress += inet.destination().sizeof();
            buffer.putShort(progress, (short) inet.sourcePort(), BIG_ENDIAN);
            progress += Short.BYTES;
            buffer.putShort(progress, (short) inet.destinationPort(), BIG_ENDIAN);
            progress += Short.BYTES;
            return progress;
        }

        private int encodeProxyAddressInet6(
            MutableDirectBuffer buffer,
            int progress,
            ProxyAddressFW address)
        {
            ProxyAddressInet6FW inet6 = address.inet6();
            buffer.putByte(progress++, (byte) (0x20 | (inet6.protocol().get().ordinal() + 1)));
            progress += Short.BYTES;
            buffer.putBytes(progress, inet6.source().value(), 0, inet6.source().sizeof());
            progress += inet6.source().sizeof();
            buffer.putBytes(progress, inet6.destination().value(), 0, inet6.destination().sizeof());
            progress += inet6.destination().sizeof();
            buffer.putShort(progress, (short) inet6.sourcePort(), BIG_ENDIAN);
            progress += Short.BYTES;
            buffer.putShort(progress, (short) inet6.destinationPort(), BIG_ENDIAN);
            progress += Short.BYTES;
            return progress;
        }

        private int encodeProxyAddressUnix(
            MutableDirectBuffer buffer,
            int progress,
            ProxyAddressFW address)
        {
            ProxyAddressUnixFW unix = address.unix();
            buffer.putByte(progress++, (byte) (0x30 | (unix.protocol().get().ordinal() + 1)));
            progress += Short.BYTES;
            buffer.putBytes(progress, unix.source().value(), 0, unix.source().sizeof());
            progress += unix.source().sizeof();
            buffer.putBytes(progress, unix.destination().value(), 0, unix.destination().sizeof());
            progress += unix.destination().sizeof();
            return progress;
        }

        private int encodeProxyTlvs(
            MutableDirectBuffer buffer,
            int progress,
            Array32FW<ProxyInfoFW> infos)
        {
            DirectBuffer items = infos.items();
            for (int itemOffset = 0; itemOffset < items.capacity(); )
            {
                ProxyInfoFW info = infoRO.wrap(items, itemOffset, items.capacity());
                switch (info.kind())
                {
                case ALPN:
                    progress = encodeProxyTlvAlpn(buffer, progress, info);
                    itemOffset = info.limit();
                    break;
                case AUTHORITY:
                    progress = encodeProxyTlvAuthority(buffer, progress, info);
                    itemOffset = info.limit();
                    break;
                case IDENTITY:
                    progress = encodeProxyTlvUniqueId(buffer, progress, info);
                    itemOffset = info.limit();
                    break;
                case SECURE:
                    buffer.putByte(progress++, (byte) 0x20);
                    int secureInfoOffset = progress;
                    progress += Short.BYTES;
                    buffer.putByte(progress, (byte) 0x07);
                    progress += Byte.BYTES;
                    buffer.putInt(progress, 0, BIG_ENDIAN);
                    progress += Integer.BYTES;
                    while (itemOffset < items.capacity() && info.kind() == SECURE)
                    {
                        info = infoRO.wrap(items, itemOffset, items.capacity());
                        ProxySecureInfoFW secureInfo = info.secure();
                        switch (secureInfo.kind())
                        {
                        case PROTOCOL:
                            progress = encodeProxyTlvSslVersion(buffer, progress, secureInfo);
                            break;
                        case NAME:
                            progress = encodeProxyTlvSslCommonName(buffer, progress, secureInfo);
                            break;
                        case CIPHER:
                            progress = encodeProxyTlvSslCipher(buffer, progress, secureInfo);
                            break;
                        case SIGNATURE:
                            progress = encodeProxyTlvSslSignature(buffer, progress, secureInfo);
                            break;
                        case KEY:
                            progress = encodeProxyTlvSslKey(buffer, progress, secureInfo);
                            break;
                        }
                        itemOffset = info.limit();
                    }

                    buffer.putShort(secureInfoOffset,
                            (short) (progress - secureInfoOffset - Short.BYTES), BIG_ENDIAN);
                    break;
                case NAMESPACE:
                    progress = encodeProxyTlvNamespace(buffer, progress, info);
                    itemOffset = info.limit();
                    break;
                default:
                    itemOffset = info.limit();
                    break;
                }
            }
            return progress;
        }

        private int encodeProxyTlvAlpn(
            MutableDirectBuffer buffer,
            int progress,
            ProxyInfoFW info)
        {
            DirectBuffer alpn = info.alpn().value();
            ProxyTlvFW alpnTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                 .type(0x01)
                 .value(alpn, 0, alpn.capacity())
                 .build();
            progress += alpnTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvAuthority(
            MutableDirectBuffer buffer,
            int progress,
            ProxyInfoFW info)
        {
            DirectBuffer authority = info.authority().value();
            ProxyTlvFW authorityTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                 .type(0x02)
                 .value(authority, 0, authority.capacity())
                 .build();
            progress += authorityTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvUniqueId(
            MutableDirectBuffer buffer,
            int progress,
            ProxyInfoFW info)
        {
            OctetsFW identity = info.identity().value();
            ProxyTlvFW identityTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                 .type(0x05)
                 .value(identity)
                 .build();
            progress += identityTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvSslKey(
            MutableDirectBuffer buffer,
            int progress,
            ProxySecureInfoFW secureInfo)
        {
            DirectBuffer key = secureInfo.key().value();
            ProxyTlvFW keyTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                .type(0x25)
                .value(key, 0, key.capacity())
                .build();
            progress += keyTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvSslSignature(
            MutableDirectBuffer buffer,
            int progress,
            ProxySecureInfoFW secureInfo)
        {
            DirectBuffer signature = secureInfo.signature().value();
            ProxyTlvFW signatureTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                .type(0x24)
                .value(signature, 0, signature.capacity())
                .build();
            progress += signatureTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvSslCipher(
            MutableDirectBuffer buffer,
            int progress,
            ProxySecureInfoFW secureInfo)
        {
            DirectBuffer cipher = secureInfo.cipher().value();
            ProxyTlvFW cipherTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                .type(0x23)
                .value(cipher, 0, cipher.capacity())
                .build();
            progress += cipherTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvSslCommonName(
            MutableDirectBuffer buffer,
            int progress,
            ProxySecureInfoFW secureInfo)
        {
            DirectBuffer commonName = secureInfo.name().value();
            ProxyTlvFW commonNameTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                .type(0x22)
                .value(commonName, 0, commonName.capacity())
                .build();
            progress += commonNameTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvSslVersion(
            MutableDirectBuffer buffer,
            int progress,
            ProxySecureInfoFW secureInfo)
        {
            DirectBuffer version = secureInfo.protocol().value();
            ProxyTlvFW versionTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                .type(0x21)
                .value(version, 0, version.capacity())
                .build();
            progress += versionTlv.sizeof();
            return progress;
        }

        private int encodeProxyTlvNamespace(
            MutableDirectBuffer buffer,
            int progress,
            ProxyInfoFW info)
        {
            DirectBuffer namespace = info.namespace().value();
            ProxyTlvFW namespaceTlv = tlvRW.wrap(buffer, progress, buffer.capacity())
                 .type(0x30)
                 .value(namespace, 0, namespace.capacity())
                 .build();
            progress += namespaceTlv.sizeof();
            return progress;
        }
    }

    private void doBegin(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long affinity)
    {
        BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .affinity(affinity)
                .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    void doData(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        int flags,
        long budgetId,
        int reserved,
        OctetsFW payload)
    {
        DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .flags(flags)
                .budgetId(budgetId)
                .reserved(reserved)
                .payload(payload)
                .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doReset(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    void doWindow(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int credit,
        int padding)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .credit(credit)
                .padding(padding)
                .build();

        receiver.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doChallenge(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        OctetsFW extension)
    {
        final ChallengeFW challenge = challengeRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension)
                .build();

        receiver.accept(challenge.typeId(), challenge.buffer(), challenge.offset(), challenge.sizeof());
    }

    void doEnd(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    void doAbort(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doFlush(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int reserved)
    {
        final FlushFW flush = flushRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .build();

        receiver.accept(flush.typeId(), flush.buffer(), flush.offset(), flush.sizeof());
    }
}
