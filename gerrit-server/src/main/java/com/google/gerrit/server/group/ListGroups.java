// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.group;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gerrit.common.data.GroupDescriptions;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.common.errors.NoSuchGroupException;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.OutputFormat;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.account.GetGroups;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupComparator;
import com.google.gerrit.server.account.GroupControl;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.util.Url;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.kohsuke.args4j.Option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** List groups visible to the calling user. */
public class ListGroups implements RestReadView<TopLevelResource> {

  protected final GroupCache groupCache;

  private final GroupControl.Factory groupControlFactory;
  private final Provider<IdentifiedUser> identifiedUser;
  private final IdentifiedUser.GenericFactory userFactory;
  private final Provider<GetGroups> accountGetGroups;

  @Option(name = "--project", aliases = {"-p"},
      usage = "projects for which the groups should be listed")
  private final List<ProjectControl> projects = new ArrayList<ProjectControl>();

  @Option(name = "--visible-to-all", usage = "to list only groups that are visible to all registered users")
  private boolean visibleToAll;

  @Option(name = "--type", usage = "type of group")
  private AccountGroup.Type groupType;

  @Option(name = "--user", aliases = {"-u"},
      usage = "user for which the groups should be listed")
  private Account.Id user;

  @Option(name = "-m", metaVar = "MATCH", usage = "match group substring")
  private String matchSubstring;

  @Inject
  protected ListGroups(final GroupCache groupCache,
      final GroupControl.Factory groupControlFactory,
      final Provider<IdentifiedUser> identifiedUser,
      final IdentifiedUser.GenericFactory userFactory,
      final Provider<GetGroups> accountGetGroups) {
    this.groupCache = groupCache;
    this.groupControlFactory = groupControlFactory;
    this.identifiedUser = identifiedUser;
    this.userFactory = userFactory;
    this.accountGetGroups = accountGetGroups;
  }

  public Account.Id getUser() {
    return user;
  }

  public List<ProjectControl> getProjects() {
    return projects;
  }

  @Override
  public Object apply(TopLevelResource resource) throws AuthException,
      BadRequestException, ResourceConflictException, Exception {
    final Map<String, GroupInfo> output = Maps.newTreeMap();
    for (GroupInfo info : get()) {
      output.put(Objects.firstNonNull(
          info.name,
          "Group " + Url.decode(info.id)), info);
      info.name = null;
    }
    return OutputFormat.JSON.newGson().toJsonTree(output,
        new TypeToken<Map<String, GroupInfo>>() {}.getType());
  }

  public List<GroupInfo> get() throws NoSuchGroupException {
    List<GroupInfo> groupInfos;
    if (user != null) {
      groupInfos = accountGetGroups.get().apply(
          new AccountResource(userFactory.create(user)));
    } else {
      List<AccountGroup> groupList;
      if (!projects.isEmpty()) {
        Map<AccountGroup.UUID, AccountGroup> groups = Maps.newHashMap();
        for (final ProjectControl projectControl : projects) {
          final Set<GroupReference> groupsRefs = projectControl.getAllGroups();
          for (final GroupReference groupRef : groupsRefs) {
            final AccountGroup group = groupCache.get(groupRef.getUUID());
            if (group == null) {
              throw new NoSuchGroupException(groupRef.getUUID());
            }
            groups.put(group.getGroupUUID(), group);
          }
        }
        groupList = filterGroups(groups.values());
      } else {
        groupList = filterGroups(groupCache.all());
      }
      groupInfos = Lists.newArrayListWithCapacity(groupList.size());
      for (AccountGroup group : groupList) {
        groupInfos.add(new GroupInfo(GroupDescriptions.forAccountGroup(group)));
      }
    }
    return groupInfos;
  }

  private List<AccountGroup> filterGroups(final Iterable<AccountGroup> groups) {
    final List<AccountGroup> filteredGroups = Lists.newArrayList();
    final boolean isAdmin =
        identifiedUser.get().getCapabilities().canAdministrateServer();
    for (final AccountGroup group : groups) {
      if (!Strings.isNullOrEmpty(matchSubstring)) {
        if (!group.getName().toLowerCase(Locale.US)
            .contains(matchSubstring.toLowerCase(Locale.US))) {
          continue;
        }
      }
      if (!isAdmin) {
        final GroupControl c = groupControlFactory.controlFor(group);
        if (!c.isVisible()) {
          continue;
        }
      }
      if ((visibleToAll && !group.isVisibleToAll())
          || (groupType != null && !groupType.equals(group.getType()))) {
        continue;
      }
      filteredGroups.add(group);
    }
    Collections.sort(filteredGroups, new GroupComparator());
    return filteredGroups;
  }
}
