/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import javax.annotation.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.MoreFutures.getDone;
import static io.airlift.slice.SizeOf.sizeOf;

public class SpatialJoinOperator
        implements Operator
{
    public static final class SpatialJoinOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final List<Type> probeTypes;
        private final List<Integer> probeOutputChannels;
        private final List<Type> probeOutputTypes;
        private final List<Type> buildOutputTypes;
        private final int probeGeometryChannel;
        private final PagesSpatialIndexFactory pagesSpatialIndexFactory;

        private boolean closed;

        public SpatialJoinOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                List<Type> probeTypes,
                List<Integer> probeOutputChannels,
                int probeGeometryChannel,
                PagesSpatialIndexFactory pagesSpatialIndexFactory)
        {
            this.operatorId = operatorId;
            this.planNodeId = planNodeId;
            this.probeTypes = ImmutableList.copyOf(probeTypes);
            this.probeOutputTypes = probeOutputChannels.stream()
                    .map(probeTypes::get)
                    .collect(toImmutableList());
            this.buildOutputTypes = pagesSpatialIndexFactory.getOutputTypes();
            this.probeOutputChannels = ImmutableList.copyOf(probeOutputChannels);
            this.probeGeometryChannel = probeGeometryChannel;
            this.pagesSpatialIndexFactory = pagesSpatialIndexFactory;
        }

        @Override
        public List<Type> getTypes()
        {
            return ImmutableList.<Type>builder()
                    .addAll(probeOutputTypes)
                    .addAll(buildOutputTypes)
                    .build();
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(
                    operatorId,
                    planNodeId,
                    SpatialJoinOperator.class.getSimpleName());
            return new SpatialJoinOperator(
                    operatorContext,
                    getTypes(),
                    probeTypes,
                    probeOutputChannels,
                    probeGeometryChannel,
                    pagesSpatialIndexFactory);
        }

        @Override
        public void noMoreOperators()
        {
            if (closed) {
                return;
            }

            pagesSpatialIndexFactory.noMoreProbeOperators();
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new SpatialJoinOperatorFactory(operatorId, planNodeId, probeTypes, probeOutputChannels, probeGeometryChannel, pagesSpatialIndexFactory);
        }
    }

    private final OperatorContext operatorContext;
    private final LocalMemoryContext localUserMemoryContext;
    private final List<Type> probeTypes;
    private final List<Type> outputTypes;
    private final List<Integer> probeOutputChannels;
    private final int probeGeometryChannel;
    private final PagesSpatialIndexFactory pagesSpatialIndexFactory;

    private ListenableFuture<PagesSpatialIndex> pagesSpatialIndexFuture;
    private final PageBuilder pageBuilder;
    @Nullable
    private Page probe;

    // The following fields represent the state of the operator in case when processProbe yielded or
    // filled up pageBuilder before processing all records in a probe page.
    private int probePosition;
    @Nullable
    private int[] joinPositions;
    private int nextJoinPositionIndex;

    private boolean finishing;
    private boolean finished;

    public SpatialJoinOperator(
            OperatorContext operatorContext,
            List<Type> outputTypes,
            List<Type> probeTypes,
            List<Integer> probeOutputChannels,
            int probeGeometryChannel,
            PagesSpatialIndexFactory pagesSpatialIndexFactory)
    {
        this.operatorContext = operatorContext;
        this.localUserMemoryContext = operatorContext.localUserMemoryContext();
        this.probeTypes = ImmutableList.copyOf(probeTypes);
        this.probeOutputChannels = ImmutableList.copyOf(probeOutputChannels);
        this.probeGeometryChannel = probeGeometryChannel;
        this.pagesSpatialIndexFactory = pagesSpatialIndexFactory;
        this.pagesSpatialIndexFuture = pagesSpatialIndexFactory.createPagesSpatialIndex();
        this.outputTypes = ImmutableList.copyOf(outputTypes);
        this.pageBuilder = new PageBuilder(outputTypes);
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<Type> getTypes()
    {
        return outputTypes;
    }

    @Override
    public boolean needsInput()
    {
        return !finished && pagesSpatialIndexFuture.isDone() && !pageBuilder.isFull() && probe == null;
    }

    @Override
    public void addInput(Page page)
    {
        verify(probe == null);
        probe = page;
        probePosition = 0;

        joinPositions = null;
    }

    @Override
    public Page getOutput()
    {
        verify(!finished);
        if (!pageBuilder.isFull() && probe != null) {
            processProbe();
        }

        if (pageBuilder.isFull()) {
            Page page = pageBuilder.build();
            pageBuilder.reset();
            return page;
        }

        if (finishing && probe == null) {
            Page page = null;
            if (!pageBuilder.isEmpty()) {
                page = pageBuilder.build();
                pageBuilder.reset();
            }
            pagesSpatialIndexFactory.probeOperatorFinished();
            pagesSpatialIndexFuture = null;
            finished = true;
            return page;
        }

        return null;
    }

    private void processProbe()
    {
        verify(probe != null);

        PagesSpatialIndex pagesSpatialIndex = getDone(pagesSpatialIndexFuture);
        DriverYieldSignal yieldSignal = operatorContext.getDriverContext().getYieldSignal();
        while (probePosition < probe.getPositionCount()) {
            if (joinPositions == null) {
                joinPositions = pagesSpatialIndex.findJoinPositions(probePosition, probe, probeGeometryChannel);
                localUserMemoryContext.setBytes(sizeOf(joinPositions));
                nextJoinPositionIndex = 0;
                if (yieldSignal.isSet()) {
                    return;
                }
            }

            while (nextJoinPositionIndex < joinPositions.length) {
                if (pageBuilder.isFull()) {
                    return;
                }

                int joinPosition = joinPositions[nextJoinPositionIndex];

                if (pagesSpatialIndex.isJoinPositionEligible(joinPosition, probePosition, probe)) {
                    pageBuilder.declarePosition();
                    int outputChannelOffset = 0;
                    for (int outputIndex : probeOutputChannels) {
                        Type type = probeTypes.get(outputIndex);
                        Block block = probe.getBlock(outputIndex);
                        type.appendTo(block, probePosition, pageBuilder.getBlockBuilder(outputChannelOffset));
                        outputChannelOffset++;
                    }
                    pagesSpatialIndex.appendTo(joinPosition, pageBuilder, outputChannelOffset);
                }

                nextJoinPositionIndex++;

                if (yieldSignal.isSet()) {
                    return;
                }
            }

            joinPositions = null;
            localUserMemoryContext.setBytes(0);
            probePosition++;
        }

        this.probe = null;
        this.probePosition = 0;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public void close()
    {
        pagesSpatialIndexFuture = null;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }
}
