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

import com.axelor.exception.service.TraceBackService;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.Project;
import java.util.List;

public class RedmineProjectFetchImportDataService {

  public List<Project> fetchImportData(RedmineManager redmineManager) {

    List<Project> importProjectList = null;

    try {
      importProjectList = redmineManager.getProjectManager().getProjects();
    } catch (RedmineException e) {
      TraceBackService.trace(e);
    }

    return importProjectList;
  }
}
