/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
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
package com.axelor.apps.redmine.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.redmine.exports.service.RedmineExportIssueService;
import com.axelor.apps.redmine.exports.service.RedmineExportIssueServiceImpl;
import com.axelor.apps.redmine.exports.service.RedmineExportProjectService;
import com.axelor.apps.redmine.exports.service.RedmineExportProjectServiceImpl;
import com.axelor.apps.redmine.exports.service.RedmineExportTimeSpentService;
import com.axelor.apps.redmine.exports.service.RedmineExportTimeSpentServiceImpl;
import com.axelor.apps.redmine.imports.service.RedmineImportIssueService;
import com.axelor.apps.redmine.imports.service.RedmineImportIssueServiceImpl;
import com.axelor.apps.redmine.imports.service.RedmineImportProjectService;
import com.axelor.apps.redmine.imports.service.RedmineImportProjectServiceImpl;
import com.axelor.apps.redmine.imports.service.RedmineImportTimeSpentService;
import com.axelor.apps.redmine.imports.service.RedmineImportTimeSpentServiceImpl;
import com.axelor.apps.redmine.issue.sync.RedmineSyncIssueService;
import com.axelor.apps.redmine.issue.sync.RedmineSyncIssueServiceImpl;
import com.axelor.apps.redmine.project.sync.RedmineSyncProjectService;
import com.axelor.apps.redmine.project.sync.RedmineSyncProjectServiceImpl;
import com.axelor.apps.redmine.service.RedmineService;
import com.axelor.apps.redmine.service.RedmineServiceImpl;

public class RedmineModule extends AxelorModule {

  @Override
  protected void configure() {

    bind(RedmineService.class).to(RedmineServiceImpl.class);
    bind(RedmineSyncIssueService.class).to(RedmineSyncIssueServiceImpl.class);
    bind(RedmineSyncProjectService.class).to(RedmineSyncProjectServiceImpl.class);

    // Import Methods
    bind(RedmineImportProjectService.class).to(RedmineImportProjectServiceImpl.class);
    bind(RedmineImportIssueService.class).to(RedmineImportIssueServiceImpl.class);
    bind(RedmineImportTimeSpentService.class).to(RedmineImportTimeSpentServiceImpl.class);

    // Export Methods
    bind(RedmineExportProjectService.class).to(RedmineExportProjectServiceImpl.class);
    bind(RedmineExportIssueService.class).to(RedmineExportIssueServiceImpl.class);
    bind(RedmineExportTimeSpentService.class).to(RedmineExportTimeSpentServiceImpl.class);
  }
}
