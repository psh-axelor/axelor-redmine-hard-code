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
package com.axelor.apps.redmine.message;

public interface IMessage {

  static final String BATCH_SYNC_SUCCESS = /*$$(*/ "Redmine sync completed" /*)*/;

  static final String REDMINE_AUTHENTICATION_1 = /*$$(*/
      "URI and API Access Key should not be empty" /*)*/;

  static final String REDMINE_AUTHENTICATION_2 = /*$$(*/
      "Please check your authentication details" /*)*/;

  static final String REDMINE_TRANSPORT = /*$$(*/
      "Error connecting redmine server. Please check the configuration" /*)*/;

  static final String REDMINE_SYNC_ERROR_RECORD_NOT_FOUND = /*$$(*/
      "No record found with linked Redmine Id" /*)*/;

  static final String REDMINE_SYNC_ERROR_ASSIGNEE_IS_NOT_VALID = /*$$(*/
      "Assignee have no membership for associated project" /*)*/;

  static final String REDMINE_SYNC_EXPORT_ERROR = /*$$(*/ "Export" /*)*/;

  static final String REDMINE_SYNC_IMPORT_ERROR = /*$$(*/ "Import" /*)*/;

  static final String REDMINE_SYNC_TEAMTASK_ERROR = /*$$(*/ "TeamTask" /*)*/;

  static final String REDMINE_SYNC_PROJECT_ERROR = /*$$(*/ "Project" /*)*/;

  static final String REDMINE_SYNC_TIMESHEET_LINE_ERROR = /*$$(*/ "TimesheetLine" /*)*/;

  static final String REDMINE_SYNC_PRODUCT_FIELD_NOT_SET = /*$$(*/
      "Entity not exported, product field not set" /*)*/;

  static final String REDMINE_SYNC_ERROR_PARENT_TASK_NOT_FOUND = /*$$(*/
      "Entity not exported, no parent task found in Redmine" /*)*/;

  static final String REDMINE_SYNC_REDMINE_PROJECT_NOT_FOUND = /*$$(*/
      "Entity not exported, no project found in Redmine" /*)*/;

  static final String REDMINE_SYNC_TRACKER_NOT_FOUND = /*$$(*/
      "Entity not exported, no tracker found in Redmine" /*)*/;

  static final String REDMINE_SYNC_ISSUE_NOT_FOUND = /*$$(*/
      "Entity not exported, no issue found in Redmine" /*)*/;

  static final String REDMINE_SYNC_EXPORT_WITH_DEFAULT_STATUS = /*$$(*/
      "Issue status not found in Redmine, exported with default status" /*)*/;

  static final String REDMINE_SYNC_EXPORT_WITH_DEFAULT_PRIORITY = /*$$(*/
      "Entity priority not found in Redmine, exported with default priority" /*)*/;

  static final String REDMINE_SYNC_REDMINE_PARENT_PROJECT_NOT_FOUND = /*$$(*/
      "Parent project not found on Redmine while it's set in OS" /*)*/;

  static final String REDMINE_SYNC_CLIENT_PARTNER_FIELD_NOT_SET = /*$$(*/
      "Client partner field not set" /*)*/;

  static final String REDMINE_SYNC_INVOICING_SEQUENCE_SELECT_FIELD_NOT_SET = /*$$(*/
      "Invoicing sequence field not set" /*)*/;

  static final String REDMINE_SYNC_CUSTOM_FIELD_PRODUCT_NOT_FOUND = /*$$(*/
      "Entity not imported, custom field 'Product' value not found" /*)*/;

  static final String REDMINE_SYNC_PARENT_TASK_NOT_FOUND = /*$$(*/
      "Entity not imported, no parent task found in OS" /*)*/;

  static final String REDMINE_SYNC_PROJECT_NOT_FOUND = /*$$(*/
      "Entity not imported, no project found in OS" /*)*/;

  static final String REDMINE_SYNC_PROJECT_CATEGORY_NOT_FOUND = /*$$(*/
      "Entity not imported, no project category found in OS" /*)*/;

  static final String REDMINE_SYNC_IMPORT_WITH_DEFAULT_STATUS = /*$$(*/
      "Teamtask status not found in OS, imported with default status" /*)*/;

  static final String REDMINE_SYNC_IMPORT_WITH_DEFAULT_PRIORITY = /*$$(*/
      "Teamtask priority not found in OS, imported with default priority" /*)*/;

  static final String REDMINE_SYNC_CUSTOM_FIELD_CUSTOMER_CODE_NOT_FOUND = /*$$(*/
      "Custom field 'Customer Code' value not found" /*)*/;

  static final String REDMINE_SYNC_CUSTOM_FIELD_INVOICING_TYPE_NOT_FOUND = /*$$(*/
      "Custom field 'Invoicing Type' value not found" /*)*/;

  static final String REDMINE_SYNC_PARENT_PROJECT_NOT_FOUND = /*$$(*/
      "Parent project not found on OS while it's set in redmine" /*)*/;

  static final String REDMINE_SYNC_TEAM_TASK_NOT_FOUND = /*$$(*/
      "Entity not imported, no teamtask found in OS" /*)*/;
}
