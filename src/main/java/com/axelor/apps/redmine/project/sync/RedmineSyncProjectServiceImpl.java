/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmine.project.sync;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.project.db.Project;
import com.axelor.apps.redmine.db.RedmineSyncMapping;
import com.axelor.apps.redmine.db.repo.RedmineSyncMappingRepository;
import com.axelor.apps.redmine.exports.service.RedmineExportProjectService;
import com.axelor.apps.redmine.imports.service.RedmineImportProjectService;
import com.axelor.apps.redmine.log.service.RedmineErrorLogService;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.RedmineManager;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

public class RedmineSyncProjectServiceImpl implements RedmineSyncProjectService {

  protected RedmineExportProjectService redmineExportProjectService;
  protected RedmineImportProjectService redmineImportProjectService;
  protected RedmineProjectFetchExportDataService redmineProjectFetchExportDataService;
  protected RedmineProjectFetchImportDataService redmineProjectFetchImportDataService;
  protected RedmineErrorLogService redmineErrorLogService;
  protected BatchRepository batchRepo;
  protected RedmineSyncMappingRepository redmineSyncMappingRepository;

  @Inject
  public RedmineSyncProjectServiceImpl(
      RedmineExportProjectService redmineExportProjectService,
      RedmineImportProjectService redmineImportProjectService,
      RedmineProjectFetchExportDataService redmineProjectFetchExportDataService,
      RedmineProjectFetchImportDataService redmineProjectFetchImportDataService,
      RedmineErrorLogService redmineErrorLogService,
      BatchRepository batchRepo,
      RedmineSyncMappingRepository redmineSyncMappingRepository) {

    this.redmineExportProjectService = redmineExportProjectService;
    this.redmineImportProjectService = redmineImportProjectService;
    this.redmineProjectFetchExportDataService = redmineProjectFetchExportDataService;
    this.redmineProjectFetchImportDataService = redmineProjectFetchImportDataService;
    this.redmineErrorLogService = redmineErrorLogService;
    this.batchRepo = batchRepo;
    this.redmineSyncMappingRepository = redmineSyncMappingRepository;
  }

  @Override
  public void redmineSyncProject(
      Batch batch,
      RedmineManager redmineManager,
      Consumer<Object> onSuccess,
      Consumer<Throwable> onError) {

    RedmineSyncService.result = "";

    // LOG REDMINE SYNC ERROR DATA

    List<Object[]> errorObjList = new ArrayList<Object[]>();

    // FETCH EXPORT AND IMPORT DATA

    Batch lastBatch =
        batchRepo
            .all()
            .filter(
                "self.id != ?1 and self.redmineBatch.id = ?2",
                batch.getId(),
                batch.getRedmineBatch().getId())
            .order("-updatedOn")
            .fetchOne();

    ZonedDateTime lastBatchEndDate = lastBatch != null ? lastBatch.getEndDate() : null;
    LocalDateTime lastBatchUpdatedOn = lastBatch != null ? lastBatch.getUpdatedOn() : null;

    List<Project> exportProjectList =
        redmineProjectFetchExportDataService.fetchExportData(
            lastBatchEndDate != null ? lastBatchEndDate.toLocalDateTime() : null);
    List<com.taskadapter.redmineapi.bean.Project> importProjectList =
        redmineProjectFetchImportDataService.fetchImportData(redmineManager);

    // CREATE MAP FOR PASS TO THE METHODS

    HashMap<String, Object> paramsMap = new HashMap<String, Object>();

    paramsMap.put("onError", onError);
    paramsMap.put("onSuccess", onSuccess);
    paramsMap.put("batch", batch);
    paramsMap.put("redmineIssueManager", redmineManager.getIssueManager());
    paramsMap.put("redmineUserManager", redmineManager.getUserManager());
    paramsMap.put("redmineProjectManager", redmineManager.getProjectManager());
    paramsMap.put("redmineTransport", redmineManager.getTransport());
    paramsMap.put("errorObjList", errorObjList);
    paramsMap.put("lastBatchUpdatedOn", lastBatchUpdatedOn);

    // MAPPING CONFIG FOR SELECTIONS

    HashMap<String, String> importFieldMap = new HashMap<String, String>();
    HashMap<String, String> exportFieldMap = new HashMap<String, String>();

    List<RedmineSyncMapping> redmineSyncMappingList = redmineSyncMappingRepository.all().fetch();

    for (RedmineSyncMapping redmineSyncMapping : redmineSyncMappingList) {
      importFieldMap.put(redmineSyncMapping.getRedmineValue(), redmineSyncMapping.getOsValue());
      exportFieldMap.put(redmineSyncMapping.getOsValue(), redmineSyncMapping.getRedmineValue());
    }

    // EXPORT PROCESS

    redmineExportProjectService.exportProject(exportProjectList, paramsMap, exportFieldMap);

    // IMPORT PROCESS

    redmineImportProjectService.importProject(importProjectList, paramsMap, importFieldMap);

    // ATTACH ERROR LOG WITH BATCH

    if (errorObjList != null && errorObjList.size() > 0) {
      MetaFile errorMetaFile = redmineErrorLogService.redmineErrorLogService(errorObjList);

      if (errorMetaFile != null) {
        batch.setErrorLogFile(errorMetaFile);
      }
    }
  }
}
