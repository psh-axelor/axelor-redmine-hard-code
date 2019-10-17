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
package com.axelor.apps.redmine.issue.sync;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.redmine.db.RedmineSyncMapping;
import com.axelor.apps.redmine.db.repo.RedmineSyncMappingRepository;
import com.axelor.apps.redmine.exports.service.RedmineExportIssueService;
import com.axelor.apps.redmine.exports.service.RedmineExportTimeSpentService;
import com.axelor.apps.redmine.imports.service.RedmineImportIssueService;
import com.axelor.apps.redmine.imports.service.RedmineImportTimeSpentService;
import com.axelor.apps.redmine.log.service.RedmineErrorLogService;
import com.axelor.apps.redmine.sync.service.RedmineSyncService;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaStore;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.views.Selection.Option;
import com.axelor.team.db.TeamTask;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.Issue;
import com.taskadapter.redmineapi.bean.TimeEntry;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;

public class RedmineSyncIssueServiceImpl implements RedmineSyncIssueService {

  protected RedmineExportIssueService redmineExportIssueService;
  protected RedmineExportTimeSpentService redmineExportTimeSpentService;
  protected RedmineImportIssueService redmineImportIssueService;
  protected RedmineImportTimeSpentService redmineImportTimeSpentService;
  protected RedmineIssueFetchExportDataService redmineIssueFetchExportDataService;
  protected RedmineIssueFetchImportDataService redmineIssueFetchImportDataService;
  protected RedmineErrorLogService redmineErrorLogService;
  protected BatchRepository batchRepo;
  protected RedmineSyncMappingRepository redmineSyncMappingRepository;

  @Inject
  public RedmineSyncIssueServiceImpl(
      RedmineExportIssueService redmineExportIssueService,
      RedmineExportTimeSpentService redmineExportTimeSpentService,
      RedmineImportIssueService redmineImportIssueService,
      RedmineImportTimeSpentService redmineImportTimeSpentService,
      RedmineIssueFetchExportDataService redmineIssueFetchExportDataService,
      RedmineIssueFetchImportDataService redmineIssueFetchImportDataService,
      RedmineErrorLogService redmineErrorLogService,
      BatchRepository batchRepo,
      RedmineSyncMappingRepository redmineSyncMappingRepository) {

    this.redmineExportIssueService = redmineExportIssueService;
    this.redmineExportTimeSpentService = redmineExportTimeSpentService;
    this.redmineImportIssueService = redmineImportIssueService;
    this.redmineImportTimeSpentService = redmineImportTimeSpentService;
    this.redmineIssueFetchExportDataService = redmineIssueFetchExportDataService;
    this.redmineIssueFetchImportDataService = redmineIssueFetchImportDataService;
    this.redmineErrorLogService = redmineErrorLogService;
    this.batchRepo = batchRepo;
    this.redmineSyncMappingRepository = redmineSyncMappingRepository;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void redmineSyncIssue(
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

    Map<String, List<?>> exportDataMap =
        redmineIssueFetchExportDataService.fetchExportData(
            lastBatchEndDate != null ? lastBatchEndDate.toLocalDateTime() : null);

    Map<String, List<?>> importDataMap = new HashMap<>();
    try {
      importDataMap =
          redmineIssueFetchImportDataService.fetchImportData(redmineManager, lastBatchEndDate);
    } catch (RedmineException e) {
      e.printStackTrace();
    }

    // CREATE MAP FOR PASS TO THE METHODS

    HashMap<String, Object> paramsMap = new HashMap<String, Object>();

    paramsMap.put("onError", onError);
    paramsMap.put("onSuccess", onSuccess);
    paramsMap.put("batch", batch);
    paramsMap.put("redmineIssueManager", redmineManager.getIssueManager());
    paramsMap.put("redmineUserManager", redmineManager.getUserManager());
    paramsMap.put("redmineProjectManager", redmineManager.getProjectManager());
    paramsMap.put("redmineTimeEntryManager", redmineManager.getTimeEntryManager());
    paramsMap.put("redmineTransport", redmineManager.getTransport());
    paramsMap.put("errorObjList", errorObjList);
    paramsMap.put("lastBatchUpdatedOn", lastBatchUpdatedOn);

    // MAPPING CONFIG FOR SELECTIONS

    HashMap<String, String> importSelectionMap = new HashMap<String, String>();
    HashMap<String, String> importFieldMap = new HashMap<String, String>();
    HashMap<String, String> exportSelectionMap = new HashMap<String, String>();
    HashMap<String, String> exportFieldMap = new HashMap<String, String>();

    List<Option> selectionList = new ArrayList<Option>();
    selectionList.addAll(MetaStore.getSelectionList("team.task.status"));
    selectionList.addAll(MetaStore.getSelectionList("team.task.priority"));

    ResourceBundle fr = I18n.getBundle(Locale.FRANCE);
    ResourceBundle en = I18n.getBundle(Locale.ENGLISH);

    for (Option option : selectionList) {
      importSelectionMap.put(fr.getString(option.getTitle()), option.getValue());
      importSelectionMap.put(en.getString(option.getTitle()), option.getValue());
      exportSelectionMap.put(option.getValue(), en.getString(option.getTitle()));
    }

    List<RedmineSyncMapping> redmineSyncMappingList = redmineSyncMappingRepository.all().fetch();

    for (RedmineSyncMapping redmineSyncMapping : redmineSyncMappingList) {
      importFieldMap.put(redmineSyncMapping.getRedmineValue(), redmineSyncMapping.getOsValue());
      exportFieldMap.put(redmineSyncMapping.getOsValue(), redmineSyncMapping.getRedmineValue());
    }

    // EXPORT PROCESS

    redmineExportIssueService.exportIssue(
        (List<TeamTask>) exportDataMap.get("exportIssueList"),
        paramsMap,
        exportSelectionMap,
        exportFieldMap);
    redmineExportTimeSpentService.exportTimeSpent(
        (List<TimesheetLine>) exportDataMap.get("exportTimeEntryList"), paramsMap);

    // IMPORT PROCESS

    redmineImportIssueService.importIssue(
        (List<Issue>) importDataMap.get("importIssueList"),
        paramsMap,
        importSelectionMap,
        importFieldMap);
    redmineImportTimeSpentService.importTimeSpent(
        (List<TimeEntry>) importDataMap.get("importTimeEntryList"), paramsMap);

    // ATTACH ERROR LOG WITH BATCH

    if (errorObjList != null && errorObjList.size() > 0) {
      MetaFile errorMetaFile = redmineErrorLogService.redmineErrorLogService(errorObjList);

      if (errorMetaFile != null) {
        batch.setErrorLogFile(errorMetaFile);
      }
    }
  }
}
