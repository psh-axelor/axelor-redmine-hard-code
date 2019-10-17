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

import com.axelor.apps.hr.db.TimesheetLine;
import com.axelor.apps.hr.db.repo.TimesheetLineRepository;
import com.axelor.team.db.TeamTask;
import com.axelor.team.db.repo.TeamTaskRepository;
import com.google.inject.Inject;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedmineIssueFetchExportDataService {

  protected TeamTaskRepository teamTaskRepo;
  protected TimesheetLineRepository timesheetLineRepo;

  @Inject
  public RedmineIssueFetchExportDataService(
      TeamTaskRepository teamTaskRepo, TimesheetLineRepository timesheetLineRepo) {

    this.teamTaskRepo = teamTaskRepo;
    this.timesheetLineRepo = timesheetLineRepo;
  }

  protected LocalDateTime lastBatchEndDate;
  protected Map<String, List<?>> exportDataMap;

  public Map<String, List<?>> fetchExportData(LocalDateTime lastBatchEndDate) {

    this.lastBatchEndDate = lastBatchEndDate;
    this.exportDataMap = new HashMap<String, List<?>>();

    this.fetchExportIssueData();
    this.fetchExportTimeEntryData();

    return exportDataMap;
  }

  public void fetchExportIssueData() {

    List<TeamTask> exportIssueList = null;

    exportIssueList =
        lastBatchEndDate != null
            ? teamTaskRepo
                .all()
                .filter(
                    "self.updatedOn >= ?1 OR self.redmineId is null OR self.redmineId = 0",
                    lastBatchEndDate)
                .fetch()
            : teamTaskRepo.all().fetch();

    exportDataMap.put("exportIssueList", exportIssueList);
  }

  public void fetchExportTimeEntryData() {

    List<TimesheetLine> exportTimeEntryList = null;

    exportTimeEntryList =
        lastBatchEndDate != null
            ? timesheetLineRepo
                .all()
                .filter(
                    "self.updatedOn >= ?1 OR self.redmineId is null OR self.redmineId = 0",
                    lastBatchEndDate)
                .fetch()
            : timesheetLineRepo.all().fetch();

    exportDataMap.put("exportTimeEntryList", exportTimeEntryList);
  }
}
