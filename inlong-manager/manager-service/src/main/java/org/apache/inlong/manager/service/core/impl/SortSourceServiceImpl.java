/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.service.core.impl;

import com.google.gson.Gson;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.inlong.common.pojo.sdk.CacheZone;
import org.apache.inlong.common.pojo.sdk.CacheZoneConfig;
import org.apache.inlong.common.pojo.sdk.SortSourceConfigResponse;
import org.apache.inlong.common.pojo.sdk.Topic;
import org.apache.inlong.manager.common.pojo.sortstandalone.SortSourceClusterInfo;
import org.apache.inlong.manager.common.pojo.sortstandalone.SortSourceGroupInfo;
import org.apache.inlong.manager.common.pojo.sortstandalone.SortSourceStreamInfo;
import org.apache.inlong.manager.dao.mapper.InlongClusterEntityMapper;
import org.apache.inlong.manager.dao.mapper.InlongGroupEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSinkEntityMapper;
import org.apache.inlong.manager.service.core.SortSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of {@link SortSourceService}.
 */
@Lazy
@Service
public class SortSourceServiceImpl implements SortSourceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SortSourceServiceImpl.class);

    private static final Gson GSON = new Gson();
    private static final Set<String> SUPPORTED_MQ_TYPE = new HashSet<String>() { {
        add("PULSAR");
        add("KAFKA");
        add("TUBE");
    }};
    private static final String KEY_SERVICE_URL = "serviceUrl";
    private static final String KEY_AUTH = "authentication";
    private static final String KEY_TENANT = "tenant";
    private static final String KEY_NAME_SPACE = "namespace";

    private static final int RESPONSE_CODE_SUCCESS = 0;
    private static final int RESPONSE_CODE_NO_UPDATE = 1;
    private static final int RESPONSE_CODE_FAIL = -1;
    private static final int RESPONSE_CODE_REQ_PARAMS_ERROR = -101;

    /** key 1: cluster name, key 2: task name, value : md5 */
    private Map<String, Map<String, String>> sortSourceMd5Map = new ConcurrentHashMap<>();
    /** key 1: cluster name, key 2: task name, value : source config */
    private Map<String, Map<String, CacheZoneConfig>> sortSourceConfigMap = new ConcurrentHashMap<>();

    private long reloadInterval = 60000L;

    @Autowired
    private InlongClusterEntityMapper clusterEntityMapper;
    @Autowired
    private StreamSinkEntityMapper streamSinkEntityMapper;
    @Autowired
    private InlongGroupEntityMapper inlongGroupEntityMapper;

    @PostConstruct
    public void initialize() {
        LOGGER.info("create repository for " + SortSourceServiceImpl.class.getSimpleName());
        try {
            reload();
            setReloadTimer();
        } catch (Throwable t) {
            LOGGER.error("initialize SortSourceConfigRepository error", t);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reload() {
        LOGGER.debug("start to reload sort config.");
        try {
            reloadAllSourceConfig();
        } catch (Throwable t) {
            LOGGER.error("fail to reload all source config", t);
        }
        LOGGER.debug("end to reload config");
    }

    @Override
    public SortSourceConfigResponse getSourceConfig(
            String cluster,
            String task,
            String md5) {

        // if cluster or task are invalid
        if (StringUtils.isBlank(cluster) || StringUtils.isBlank(task)) {
            String errMsg = "blank cluster name or task name, return nothing";
            LOGGER.error(errMsg);
            return SortSourceConfigResponse.builder()
                    .code(RESPONSE_CODE_REQ_PARAMS_ERROR)
                    .msg(errMsg)
                    .build();
        }

        // if there is no config
        if (!sortSourceConfigMap.containsKey(cluster) || !sortSourceConfigMap.get(cluster).containsKey(task)) {
            String errMsg = String.format("there is no valid source config of cluster %s, task %s", cluster, task);
            LOGGER.error(errMsg);
            return SortSourceConfigResponse.builder()
                    .code(RESPONSE_CODE_REQ_PARAMS_ERROR)
                    .msg(errMsg)
                    .build();
        }

        // if the same md5
        if (sortSourceMd5Map.get(cluster).get(task).equals(md5)) {
            return SortSourceConfigResponse.builder()
                    .code(RESPONSE_CODE_NO_UPDATE)
                    .msg("No update")
                    .md5(md5)
                    .build();
        }

        // if there is bad config
        if (sortSourceConfigMap.get(cluster).get(task).getCacheZones().isEmpty()) {
            String errMsg = String.format("find empty cache zones of cluster %s, task %s, "
                    + "please check the manager log", cluster, task);
            LOGGER.error(errMsg);
            return SortSourceConfigResponse.builder()
                    .code(RESPONSE_CODE_FAIL)
                    .msg(errMsg)
                    .build();
        }

        return SortSourceConfigResponse.builder()
                .code(RESPONSE_CODE_SUCCESS)
                .msg("Success")
                .data(sortSourceConfigMap.get(cluster).get(task))
                .md5(sortSourceMd5Map.get(cluster).get(task))
                .build();

    }

    private void reloadAllSourceConfig() {

        // get all streams.
        List<SortSourceStreamInfo> allStreamInfos = streamSinkEntityMapper.selectAllStreams();

        // convert to Map<clusterName, Map<taskName, List<groupId>>> format.
        Map<String, Map<String, List<String>>> groupMap = new ConcurrentHashMap<>();
        allStreamInfos.stream()
                .filter(dto -> dto.getSortClusterName() != null && dto.getSortTaskName() != null)
                .forEach(stream -> {
                    Map<String, List<String>> task2groupsMap =
                            groupMap.computeIfAbsent(stream.getSortClusterName(), k -> new ConcurrentHashMap<>());
                    List<String> groupIdList =
                            task2groupsMap.computeIfAbsent(stream.getSortTaskName(), k -> new ArrayList<>());
                    groupIdList.add(stream.getGroupId());
                });

        // get all groups. group by group id.
        List<SortSourceGroupInfo> groupInfos = inlongGroupEntityMapper.selectAllGroups();
        Map<String, SortSourceGroupInfo> allId2GroupInfos = groupInfos.stream()
                .filter(dto -> dto.getGroupId() != null)
                .filter(group -> SUPPORTED_MQ_TYPE.contains(group.getMqType()))
                .collect(Collectors.toMap(SortSourceGroupInfo::getGroupId, dto -> dto, (g1, g2) -> g1));

        // get all clusters. filter by type and check if consumable, then group by cluster tag.
        List<SortSourceClusterInfo> clusterInfos = clusterEntityMapper.selectAllClusters();
        Map<String, List<SortSourceClusterInfo>> allTag2ClusterInfos = clusterInfos.stream()
                .filter(dto -> dto.getClusterTags() != null)
                .filter(SortSourceClusterInfo::isConsumable)
                .filter(cluster -> SUPPORTED_MQ_TYPE.contains(cluster.getType()))
                .collect(Collectors.groupingBy(SortSourceClusterInfo::getClusterTags));

        // Prepare CacheZones for each cluster and task
        Map<String, Map<String, String>> newMd5Map = new ConcurrentHashMap<>();
        Map<String, Map<String, CacheZoneConfig>> newConfigMap = new ConcurrentHashMap<>();
        groupMap.forEach((cluster, task2Group) -> {

            Map<String, CacheZoneConfig> task2Config = new ConcurrentHashMap<>();
            Map<String, String> task2Md5 = new ConcurrentHashMap<>();

            task2Group.forEach((task, groupList) -> {
                Map<String, CacheZone> cacheZones;
                try {
                    cacheZones = this.getCacheZones(groupList, allId2GroupInfos, allTag2ClusterInfos);
                } catch (Throwable t) {
                    LOGGER.error("fail to get cacheZones of cluster {}, task {}", cluster, task);
                    return;
                }
                CacheZoneConfig config = CacheZoneConfig.builder()
                        .cacheZones(cacheZones)
                        .sortClusterName(cluster)
                        .sortTaskId(task)
                        .build();
                String jsonStr = GSON.toJson(config);
                String md5 = DigestUtils.md5Hex(jsonStr);
                task2Config.put(task, config);
                task2Md5.put(task, md5);
            });

            newConfigMap.put(cluster, task2Config);
            newMd5Map.put(cluster, task2Md5);
        });

        sortSourceConfigMap = newConfigMap;
        sortSourceMd5Map = newMd5Map;
    }

    private Map<String, CacheZone> getCacheZones(
            List<String> groupIdList,
            Map<String, SortSourceGroupInfo> allId2GroupInfos,
            Map<String, List<SortSourceClusterInfo>> allTag2ClusterInfos) {

        // stream of group info if group id exists.
        List<SortSourceGroupInfo> groupInfoStream = groupIdList.stream()
                .filter(allId2GroupInfos::containsKey)
                .map(allId2GroupInfos::get)
                .collect(Collectors.toList());

        // Group them by cluster tag.
        Map<String, List<SortSourceGroupInfo>> tag2GroupInfos = groupInfoStream.stream()
                .collect(Collectors.groupingBy(SortSourceGroupInfo::getClusterTag));

        // Group them by second cluster tag if both 2nd tag and 2nd topic exist.
        Map<String, List<SortSourceGroupInfo>> secondTag2GroupInfos = groupInfoStream.stream()
                .filter(group -> group.getSecondClusterTag() != null && group.getSecondTopic() != null)
                .collect(Collectors.groupingBy(SortSourceGroupInfo::getSecondClusterTag));

        // get cache zone list.
        List<CacheZone> firstTagCacheZoneList =
                this.getCacheZoneListByTag(tag2GroupInfos, allTag2ClusterInfos, false);
        List<CacheZone> secondTagCacheZoneList =
                this.getCacheZoneListByTag(secondTag2GroupInfos, allTag2ClusterInfos, true);

        // combine two cache zone list, and group by cache zone name.
        Map<String, CacheZone> cacheZones = Stream.of(firstTagCacheZoneList, secondTagCacheZoneList)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(
                        CacheZone::getZoneName,
                        cacheZone -> cacheZone,
                        (zone1, zone2) -> {
                            zone1.getTopics().addAll(zone2.getTopics());
                            return zone1;
                        })
                );
        return cacheZones;
    }

    private List<CacheZone> getCacheZoneListByTag(
            Map<String, List<SortSourceGroupInfo>> tag2GroupInfos,
            Map<String, List<SortSourceClusterInfo>> allTag2ClusterInfos,
            boolean isSecondTag) {

        // Tags of groups
        List<String> tags = new ArrayList<>(tag2GroupInfos.keySet());

        // Clusters that related to these tags
        Map<String, List<SortSourceClusterInfo>> tag2ClusterInfos = allTag2ClusterInfos.entrySet().stream()
                .filter(entry -> tag2GroupInfos.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // get CacheZone list
        List<CacheZone> cacheZones = tags.stream()
                .filter(tag2ClusterInfos::containsKey)
                .flatMap(tag -> {
                    List<SortSourceGroupInfo> groups = tag2GroupInfos.get(tag);
                    List<SortSourceClusterInfo> clusters = tag2ClusterInfos.get(tag);
                    return clusters.stream()
                            .map(cluster -> {
                                CacheZone zone = null;
                                try {
                                    zone = this.getCacheZone(groups, cluster, isSecondTag);
                                } catch (IllegalStateException e) {
                                    LOGGER.error("fail to init cache zone for cluster " + cluster, e);
                                }
                                return zone;
                            });
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return cacheZones;
    }

    private CacheZone getCacheZone(
            List<SortSourceGroupInfo> groups,
            SortSourceClusterInfo cluster,
            boolean isSecondTag) {

        // get basic Cache zone fields
        Map<String, String> param = cluster.getExtParamsMap();
        String serviceUrl = Optional.ofNullable(param.get(KEY_SERVICE_URL))
                .orElseThrow(() ->
                        new IllegalStateException(("there is no serviceUrl for cluster " + cluster.getName())));
        String tenant = param.get(KEY_TENANT);
        String namespace = param.get(KEY_NAME_SPACE);
        String authentication = Optional.ofNullable(param.get(KEY_AUTH)).orElse("");

        List<Topic> topics = groups.stream()
                .map(groupInfo -> getTopic(groupInfo, tenant, namespace, isSecondTag))
                .collect(Collectors.toList());

        return CacheZone.builder()
                .serviceUrl(serviceUrl)
                .authentication(authentication)
                .cacheZoneProperties(param)
                .zoneName(cluster.getName())
                .zoneType(cluster.getType())
                .topics(topics)
                .build();
    }

    private Topic getTopic(
            SortSourceGroupInfo groupInfo,
            String tenant,
            String namespace,
            boolean isSecondTag) {

        String topic = isSecondTag ? groupInfo.getSecondTopic() : groupInfo.getTopic();
        StringBuilder fullTopic = new StringBuilder();
        Optional.ofNullable(tenant).ifPresent(t -> fullTopic.append(t).append("/"));
        Optional.ofNullable(namespace).ifPresent(n -> fullTopic.append(n).append("/"));
        fullTopic.append(topic);
        return Topic.builder()
                .topic(fullTopic.toString())
                .topicProperties(groupInfo.getExtParamsMap())
                .build();
    }

    /**
     * Set reload timer at the beginning of repository.
     */
    private void setReloadTimer() {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(this::reload, reloadInterval, reloadInterval, TimeUnit.MILLISECONDS);
    }
}
