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

package org.apache.inlong.manager.common.pojo.sink.iceberg;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.inlong.manager.common.enums.ErrorCodeEnum;
import org.apache.inlong.manager.common.exceptions.BusinessException;


/**
 * Iceberg column info
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcebergColumnInfo {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ApiModelProperty("Length of fixed type")
    private Integer length;

    @ApiModelProperty("Precision of decimal type")
    private Integer precision;

    @ApiModelProperty("Scale of decimal type")
    private Integer scale;

    @ApiModelProperty("Field partition strategy, including: None, Identity, Year, Month, Day, Hour, Bucket, Truncate")
    private String partitionStrategy;

    @ApiModelProperty("Bucket num param of bucket partition")
    private Integer bucketNum;

    @ApiModelProperty("Width param of truncate partition")
    private Integer width;

    // The following are passed from base field and need not be part of API for extra param
    private String name;
    private String type;
    private String desc;
    private boolean required;

    /**
     * Get the extra param from the Json
     */
    public static IcebergColumnInfo getFromJson(String extParams) {
        if (StringUtils.isEmpty(extParams)) {
            return new IcebergColumnInfo();
        }
        try {
            OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return OBJECT_MAPPER.readValue(extParams, IcebergColumnInfo.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.SINK_INFO_INCORRECT.getMessage());
        }
    }
}
