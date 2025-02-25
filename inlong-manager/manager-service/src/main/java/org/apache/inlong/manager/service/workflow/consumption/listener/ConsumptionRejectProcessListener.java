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

package org.apache.inlong.manager.service.workflow.consumption.listener;

import org.apache.inlong.manager.common.enums.ConsumptionStatus;
import org.apache.inlong.manager.common.exceptions.WorkflowListenerException;
import org.apache.inlong.manager.dao.entity.ConsumptionEntity;
import org.apache.inlong.manager.dao.mapper.ConsumptionEntityMapper;
import org.apache.inlong.manager.common.pojo.workflow.form.process.NewConsumptionProcessForm;
import org.apache.inlong.manager.workflow.WorkflowContext;
import org.apache.inlong.manager.workflow.event.ListenerResult;
import org.apache.inlong.manager.workflow.event.process.ProcessEvent;
import org.apache.inlong.manager.workflow.event.process.ProcessEventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Added data consumption process rejection event listener
 */
@Component
public class ConsumptionRejectProcessListener implements ProcessEventListener {

    private final ConsumptionEntityMapper consumptionEntityMapper;

    @Autowired
    public ConsumptionRejectProcessListener(ConsumptionEntityMapper consumptionEntityMapper) {
        this.consumptionEntityMapper = consumptionEntityMapper;
    }

    @Override
    public ProcessEvent event() {
        return ProcessEvent.REJECT;
    }

    @Override
    public ListenerResult listen(WorkflowContext context) throws WorkflowListenerException {
        NewConsumptionProcessForm processForm = (NewConsumptionProcessForm) context.getProcessForm();

        ConsumptionEntity update = new ConsumptionEntity();
        update.setId(processForm.getConsumptionInfo().getId());
        update.setStatus(ConsumptionStatus.REJECTED.getStatus());
        update.setModifyTime(new Date());

        consumptionEntityMapper.updateByPrimaryKeySelective(update);
        return ListenerResult.success("The application process was rejected");
    }
}
