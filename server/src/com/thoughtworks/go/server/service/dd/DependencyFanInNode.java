/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.service.dd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterialConfig;
import com.thoughtworks.go.domain.PipelineTimelineEntry;
import com.thoughtworks.go.domain.StageIdentifier;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.materials.dependency.DependencyMaterialRevision;
import com.thoughtworks.go.server.domain.PipelineTimeline;
import com.thoughtworks.go.server.service.NoCompatibleUpstreamRevisionsException;
import com.thoughtworks.go.util.Pair;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;

import static com.thoughtworks.go.server.service.dd.DependencyFanInNode.RevisionAlteration.ALL_OPTIONS_EXHAUSTED;
import static com.thoughtworks.go.server.service.dd.DependencyFanInNode.RevisionAlteration.ALTERED_TO_CORRECT_REVISION;
import static com.thoughtworks.go.server.service.dd.DependencyFanInNode.RevisionAlteration.NEED_MORE_REVISIONS;

public class DependencyFanInNode extends FanInNode {
    private static List<Class<? extends MaterialConfig>> DEPENDENCY_NODE_TYPES = new ArrayList<Class<? extends MaterialConfig>>();
    private int totalInstanceCount = Integer.MAX_VALUE;
    private int maxBackTrackLimit = Integer.MAX_VALUE;
    private int currentCount;
    StageIdentifier currentRevision;
    private Map<StageIdentifier, Set<FaninScmMaterial>> stageIdentifierScmMaterial = new LinkedHashMap<StageIdentifier, Set<FaninScmMaterial>>();
    public Set<FanInNode> children = new HashSet<FanInNode>();

    public Set<? extends FaninScmMaterial> stageIdentifierScmMaterialForCurrentRevision() {
        return stageIdentifierScmMaterial.get(currentRevision);
    }

    enum RevisionAlteration {
        NOT_APPLICABLE, SAME_AS_CURRENT_REVISION, ALTERED_TO_CORRECT_REVISION, ALL_OPTIONS_EXHAUSTED,
        NEED_MORE_REVISIONS
    }

    static {
        DEPENDENCY_NODE_TYPES.add(DependencyMaterialConfig.class);
    }

    DependencyFanInNode(MaterialConfig material) {
        super(material);
        for (Class<? extends MaterialConfig> clazz : DEPENDENCY_NODE_TYPES) {
            if (clazz.isAssignableFrom(material.getClass())) {
                return;
            }
        }
        throw new RuntimeException("Not a valid root node material type");
    }

    public void populateRevisions(CaseInsensitiveString pipelineName, FanInGraphContext context) {
        initialize(context);
        fillNextRevisions(context);
        if (initRevision(context) == ALL_OPTIONS_EXHAUSTED) {
            throw NoCompatibleUpstreamRevisionsException.noValidRevisionsForUpstream(pipelineName, materialConfig);
        }

    }

    private void setCurrentRevision() {
        currentRevision = stageIdentifierScmMaterial.keySet().toArray(new StageIdentifier[0])[0];
    }

    private RevisionAlteration initRevision(FanInGraphContext context) {
        if (!stageIdentifierScmMaterial.isEmpty()) {
            setCurrentRevision();
        } else {
            return handleNeedMoreRevisions(context);
        }

        return ALTERED_TO_CORRECT_REVISION;
    }

    private RevisionAlteration handleNeedMoreRevisions(FanInGraphContext context) {
        while (hasMoreInstances()) {
            fillNextRevisions(context);
            if (!stageIdentifierScmMaterial.isEmpty()) {
                setCurrentRevision();
                return ALTERED_TO_CORRECT_REVISION;
            }
        }
        return ALL_OPTIONS_EXHAUSTED;
    }

    public RevisionAlteration setRevisionTo(StageIdFaninScmMaterialPair revisionToSet, FanInGraphContext context) {
        RevisionAlteration revisionAlteration = alterRevision(revisionToSet, context);
        while (revisionAlteration == NEED_MORE_REVISIONS) {
            fillNextRevisions(context);
            revisionAlteration = alterRevision(revisionToSet, context);
        }
        return revisionAlteration;
    }

    public void initialize(FanInGraphContext context) {
        totalInstanceCount = context.pipelineTimeline.instanceCount(((DependencyMaterialConfig) materialConfig).getPipelineName());
        maxBackTrackLimit = context.maxBackTrackLimit;
    }

    public PipelineTimelineEntry latestPipelineTimelineEntry(FanInGraphContext context) {
        if (totalInstanceCount == 0) {
            return null;
        }
        return context.pipelineTimeline.instanceFor(((DependencyMaterialConfig) materialConfig).getPipelineName(), totalInstanceCount - 1);
    }

    private void fillNextRevisions(FanInGraphContext context) {
        if (!hasMoreInstances()) {
            return;
        }
        int batchOffset = currentCount;
        for (int i = 1; i <= context.revBatchCount; ++i) {
            final Pair<StageIdentifier, List<FaninScmMaterial>> sIdScmPair = getRevisionNthFor(i + batchOffset, context);
            if (!validateAllScmRevisionsAreSameWithinAFingerprint(sIdScmPair)) {
                ++currentCount;
                if (!hasMoreInstances()) {
                    break;
                }
                continue;
            }
            validateIfRevisionMatchesTheCurrentConfigAndUpdateTheMaterialMap(context, sIdScmPair);
            if (!hasMoreInstances()) {
                break;
            }
        }
    }

    private Pair<StageIdentifier, List<FaninScmMaterial>> getRevisionNthFor(int n, FanInGraphContext context) {
        List<FaninScmMaterial> scmMaterials = new ArrayList<FaninScmMaterial>();
        PipelineTimeline pipelineTimeline = context.pipelineTimeline;
        Queue<PipelineTimelineEntry.Revision> revisionQueue = new ConcurrentLinkedQueue<PipelineTimelineEntry.Revision>();
        DependencyMaterialConfig dependencyMaterial = (DependencyMaterialConfig) materialConfig;
        PipelineTimelineEntry entry = pipelineTimeline.instanceFor(dependencyMaterial.getPipelineName(), totalInstanceCount - n);

        StageIdentifier dependentStageIdentifier = dependentStageIdentifier(context, entry, CaseInsensitiveString.str(dependencyMaterial.getStageName()));
        if (!StageIdentifier.NULL.equals(dependentStageIdentifier)) {
            addToRevisionQueue(entry, revisionQueue, scmMaterials, context);
        } else {
            return null;
        }
        while (!revisionQueue.isEmpty()) {
            PipelineTimelineEntry.Revision revision = revisionQueue.poll();
            DependencyMaterialRevision dmr = DependencyMaterialRevision.create(revision.revision, null);
            PipelineTimelineEntry pte = pipelineTimeline.getEntryFor(new CaseInsensitiveString(dmr.getPipelineName()), dmr.getPipelineCounter());
            addToRevisionQueue(pte, revisionQueue, scmMaterials, context);
        }

        return new Pair<StageIdentifier, List<FaninScmMaterial>>(dependentStageIdentifier, scmMaterials);
    }

    private boolean validateAllScmRevisionsAreSameWithinAFingerprint(Pair<StageIdentifier, List<FaninScmMaterial>> pIdScmPair) {
        if (pIdScmPair == null) {
            return false;
        }
        List<FaninScmMaterial> scmMaterialList = pIdScmPair.last();
        for (final FaninScmMaterial scmMaterial : scmMaterialList) {
            Collection<FaninScmMaterial> scmMaterialOfSameFingerprint = CollectionUtils.select(scmMaterialList, new Predicate() {
                @Override
                public boolean evaluate(Object o) {
                    return scmMaterial.equals(o);
                }
            });

            for (FaninScmMaterial faninScmMaterial : scmMaterialOfSameFingerprint) {
                if (!faninScmMaterial.revision.equals(scmMaterial.revision)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void validateIfRevisionMatchesTheCurrentConfigAndUpdateTheMaterialMap(FanInGraphContext context, Pair<StageIdentifier, List<FaninScmMaterial>> stageIdentifierScmPair) {
        final Set<MaterialConfig> currentScmMaterials = context.pipelineScmDepMap.get(materialConfig);
        final Set<FaninScmMaterial> scmMaterials = new HashSet<FaninScmMaterial>(stageIdentifierScmPair.last());
        final Set<String> currentScmFingerprint = new HashSet<String>();
        for (MaterialConfig currentScmMaterial : currentScmMaterials) {
            currentScmFingerprint.add(currentScmMaterial.getFingerprint());
        }
        final Set<String> scmMaterialsFingerprint = new HashSet<String>();
        for (FaninScmMaterial scmMaterial : scmMaterials) {
            scmMaterialsFingerprint.add(scmMaterial.fingerprint);
        }
        final Collection commonMaterials = CollectionUtils.intersection(currentScmFingerprint, scmMaterialsFingerprint);
        if (commonMaterials.size() == scmMaterials.size() && commonMaterials.size() == currentScmMaterials.size()) {
            stageIdentifierScmMaterial.put(stageIdentifierScmPair.first(), scmMaterials);
            ++currentCount;
        } else {
            //This is it. We will not go beyond this revision in history
            totalInstanceCount = currentCount;
        }
    }

    private StageIdentifier dependentStageIdentifier(FanInGraphContext context, PipelineTimelineEntry entry, final String stageName) {
        return context.pipelineDao.latestPassedStageIdentifier(entry.getId(), stageName);
    }

    private void addToRevisionQueue(PipelineTimelineEntry entry, Queue<PipelineTimelineEntry.Revision> revisionQueue, List<FaninScmMaterial> scmMaterials,
                                    FanInGraphContext context) {
        for (Map.Entry<String, List<PipelineTimelineEntry.Revision>> revisionList : entry.revisions().entrySet()) {
            String fingerprint = revisionList.getKey();
            PipelineTimelineEntry.Revision revision = revisionList.getValue().get(0);
            if (isScmMaterial(fingerprint, context)) {
                scmMaterials.add(new FaninScmMaterial(fingerprint, revision));
                continue;
            }

            if (isDependencyMaterial(fingerprint, context)) {
                revisionQueue.add(revision);
            }
        }
    }

    private boolean isDependencyMaterial(String fingerprint, FanInGraphContext context) {
        return context.fingerprintDepMaterialMap.containsKey(fingerprint);
    }

    private boolean isScmMaterial(String fingerprint, FanInGraphContext context) {
        return context.fingerprintScmMaterialMap.containsKey(fingerprint);
    }

    private boolean hasMoreInstances() {
        if (currentCount > maxBackTrackLimit) {
            throw new MaxBackTrackLimitReachedException(materialConfig);
        }
        return currentCount < totalInstanceCount;
    }

    private RevisionAlteration alterRevision(StageIdFaninScmMaterialPair revisionToSet, FanInGraphContext context) {
        if (currentRevision == revisionToSet.stageIdentifier) {
            return RevisionAlteration.SAME_AS_CURRENT_REVISION;
        }
        if (!stageIdentifierScmMaterial.get(currentRevision).contains(revisionToSet.faninScmMaterial)) {
            return RevisionAlteration.NOT_APPLICABLE;
        }
        ArrayList<StageIdentifier> stageIdentifiers = new ArrayList<StageIdentifier>(stageIdentifierScmMaterial.keySet());
        int currentRevIndex = stageIdentifiers.indexOf(currentRevision);
        for (int i = currentRevIndex; i < stageIdentifiers.size(); i++) {
            final StageIdentifier key = stageIdentifiers.get(i);
            final List<FaninScmMaterial> materials = new ArrayList<FaninScmMaterial>(stageIdentifierScmMaterial.get(key));
            final int index = materials.indexOf(revisionToSet.faninScmMaterial);
            if (index == -1) {
                return ALL_OPTIONS_EXHAUSTED;
            }
            final FaninScmMaterial faninScmMaterial = materials.get(index);
            if (faninScmMaterial.revision.equals(revisionToSet.faninScmMaterial.revision)) {
                currentRevision = key;
                return ALTERED_TO_CORRECT_REVISION;
            }
            if (faninScmMaterial.revision.lessThan(revisionToSet.faninScmMaterial.revision)) {
                currentRevision = key;
                return ALTERED_TO_CORRECT_REVISION;
            }
        }

        if (!hasMoreInstances()) {
            return ALL_OPTIONS_EXHAUSTED;
        }
        return NEED_MORE_REVISIONS;
    }

    public List<StageIdFaninScmMaterialPair> getCurrentFaninScmMaterials() {
        List<StageIdFaninScmMaterialPair> stageIdScmPairs = new ArrayList<StageIdFaninScmMaterialPair>();
        Set<FaninScmMaterial> faninScmMaterials = stageIdentifierScmMaterial.get(currentRevision);
        for (FaninScmMaterial faninScmMaterial : faninScmMaterials) {
            StageIdFaninScmMaterialPair pIdScmPair = new StageIdFaninScmMaterialPair(currentRevision, faninScmMaterial);
            stageIdScmPairs.add(pIdScmPair);
        }
        return stageIdScmPairs;
    }
}
