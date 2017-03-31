/*
 * This file is part of the RUNA WFE project.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; version 2.1
 * of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.
 */
package ru.runa.wfe.security.logic;

import java.text.MessageFormat;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ru.runa.wfe.InternalApplicationException;
import ru.runa.wfe.commons.Utils;
import ru.runa.wfe.presentation.BatchPresentationFactory;
import ru.runa.wfe.security.ASystem;
import ru.runa.wfe.security.Permission;
import ru.runa.wfe.security.SystemPermission;
import ru.runa.wfe.security.dao.PermissionDAO;
import ru.runa.wfe.user.Actor;
import ru.runa.wfe.user.Executor;
import ru.runa.wfe.user.ExecutorDoesNotExistException;
import ru.runa.wfe.user.Group;
import ru.runa.wfe.user.dao.ExecutorDAO;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Imports users and group from LDAP directory.
 * 
 * @since 4.0.4
 */
@SuppressWarnings("unchecked")
public class LdapLogic {
    private static final Log log = LogFactory.getLog(LdapLogic.class);
    private static final String OBJECT_CLASS_USER_FILTER = MessageFormat.format(LdapProperties.getSynchronizationObjectClassFilter(),
            LdapProperties.getSynchronizationUserObjectClass());
    private static final String OBJECT_CLASS_GROUP_FILTER = MessageFormat.format(LdapProperties.getSynchronizationObjectClassFilter(),
            LdapProperties.getSynchronizationGroupObjectClass());
    private static final String ATTR_ACCOUNT_NAME = LdapProperties.getSynchronizationAccountNameAttribute();
    private static final String ATTR_GROUP_MEMBER = LdapProperties.getSynchronizationGroupMemberAttribute();
    // for paging
    private static final String LOGIN_FIRST_LETTER_FILTER = "(&(|({0}={1}*)({0}={2}*)){3})";
    private static final String[] ALPHABETS = { "А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "И", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У",
            "Ф", "Х", "Ч", "Ц", "Ш", "Щ", "Э", "Ю", "Я", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R",
            "S", "T", "U", "V", "W", "X", "Y", "Z" };

    @Autowired
    protected ExecutorDAO executorDAO;
    @Autowired
    private PermissionDAO permissionDAO;
    private Pattern patternForMissedPeople;
    private Group importGroup = null;
    private Group wasteGroup = null;

    public synchronized void synchronizeExecutors() {
        if (!LdapProperties.isSynchronizationEnabled()) {
            log.debug("Synchronization is disabled");
            return;
        }
        log.info("Synchronizing executors");
        try {
            importGroup = loadGroup(new Group(LdapProperties.getSynchronizationImportGroupName(),
                    LdapProperties.getSynchronizationImportGroupDescription()));
            wasteGroup = loadGroup(new Group(LdapProperties.getSynchronizationWasteGroupName(),
                    LdapProperties.getSynchronizationWasteGroupDescription()));
            DirContext dirContext = getContext();
            Map<String, Actor> actorsByDistinguishedName = synchronizeActors(dirContext);
            synchronizeGroups(dirContext, actorsByDistinguishedName);
            dirContext.close();
        } catch (Exception e) {
            log.error("", e);
            // prevent java.io.NotSerializableException: com.sun.jndi.ldap.LdapCtx
            throw new InternalApplicationException(e.getMessage());
        }
    }

    private Map<String, Actor> synchronizeActors(DirContext dirContext) throws Exception {
        List<Actor> existingActorsList = executorDAO.getAllActors(BatchPresentationFactory.ACTORS.createNonPaged());
        Map<String, Actor> existingActorsMap = Maps.newHashMap();
        for (Actor actor : existingActorsList) {
            existingActorsMap.put(actor.getName().toLowerCase(), actor);
        }
        Set<Actor> ldapActorsToDelete = Sets.newHashSet();
        if (LdapProperties.isSynchronizationDeleteExecutors()) {
            ldapActorsToDelete.addAll(executorDAO.getGroupActors(importGroup));
        }
        Map<String, Actor> actorsByDistinguishedName = Maps.newHashMap();
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        for (String ou : LdapProperties.getSynchronizationOrganizationUnits()) {
            List<SearchResult> resultList = Lists.newArrayList();
            try {
                NamingEnumeration<SearchResult> list = dirContext.search(ou, OBJECT_CLASS_USER_FILTER, controls);
                while (list.hasMore()) {
                    SearchResult searchResult = list.next();
                    resultList.add(searchResult);
                }
                list.close();
            } catch (SizeLimitExceededException e) {
                resultList.clear();
                for (String y : ALPHABETS) {
                    NamingEnumeration<SearchResult> list = dirContext.search(ou,
                            MessageFormat.format(LOGIN_FIRST_LETTER_FILTER, ATTR_ACCOUNT_NAME, y, y.toLowerCase(), OBJECT_CLASS_USER_FILTER),
                            controls);
                    while (list.hasMore()) {
                        SearchResult searchResult = list.next();
                        resultList.add(searchResult);
                    }
                    list.close();
                }
            }
            for (SearchResult searchResult : resultList) {
                String name = getStringAttribute(searchResult, ATTR_ACCOUNT_NAME);
                String fullName = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserFullNameAttribute());
                String email = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserEmailAttribute());
                String department = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserDepartmentAttribute());
                String title = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserTitleAttribute());
                String description = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserDescriptionAttribute());
                String phone = getStringAttribute(searchResult, LdapProperties.getSynchronizationUserPhoneAttribute());
                Actor actor = existingActorsMap.get(name.toLowerCase());
                if (actor == null) {
                    if (!LdapProperties.isSynchronizationCreateExecutors()) {
                        continue;
                    }
                    actor = new Actor(name, description, fullName, null, email, phone, title, department);
                    log.info("Creating " + actor);
                    executorDAO.create(actor);
                    executorDAO.addExecutorsToGroup(Lists.newArrayList(actor), importGroup);
                    permissionDAO.setPermissions(importGroup, Lists.newArrayList(Permission.READ), actor);
                } else {
                    ldapActorsToDelete.remove(actor);
                    if (LdapProperties.isSynchronizationUpdateExecutors()) {
                        List<String> updatedAttributes = Lists.newArrayList();
                        if (!Strings.isNullOrEmpty(description) && !Objects.equal(description, actor.getDescription())) {
                            actor.setDescription(description);
                            updatedAttributes.add("description");
                        }
                        if (!Strings.isNullOrEmpty(fullName) && !Objects.equal(fullName, actor.getFullName())) {
                            actor.setFullName(fullName);
                            updatedAttributes.add("fullName");
                        }
                        if (!Strings.isNullOrEmpty(email) && !Objects.equal(email, actor.getEmail())) {
                            actor.setEmail(email);
                            updatedAttributes.add("email");
                        }
                        if (!Strings.isNullOrEmpty(phone) && !Objects.equal(phone, actor.getPhone())) {
                            actor.setPhone(phone);
                            updatedAttributes.add("phone");
                        }
                        if (!actor.isActive()) {
                            actor.setActive(true);
                            updatedAttributes.add("active");
                        }
                        executorDAO.update(actor);
                        if (executorDAO.removeExecutorFromGroup(actor, wasteGroup)) {
                            updatedAttributes.add("waste group removal");
                        }
                        if (executorDAO.addExecutorToGroup(actor, importGroup)) {
                            updatedAttributes.add("import group addition");
                        }
                        if (!updatedAttributes.isEmpty()) {
                            log.info("Updating " + actor + ": " + updatedAttributes);
                        }
                    }
                }
                actorsByDistinguishedName.put(searchResult.getNameInNamespace(), actor);
            }
        }
        if (LdapProperties.isSynchronizationDeleteExecutors() && ldapActorsToDelete.size() > 0) {
            for (Actor actor : ldapActorsToDelete) {
                actor.setActive(false);
                executorDAO.update(actor);
                log.info("Deleting " + actor);
            }
            executorDAO.removeExecutorsFromGroup(ldapActorsToDelete, importGroup);
            executorDAO.addExecutorsToGroup(ldapActorsToDelete, wasteGroup);
        }
        return actorsByDistinguishedName;
    }

    private void synchronizeGroups(DirContext dirContext, Map<String, Actor> actorsByDistinguishedName) throws NamingException {
        List<Group> existingGroupsList = executorDAO.getAllGroups();
        Map<String, Group> existingGroupsByLdapNameMap = Maps.newHashMap();
        for (Group group : existingGroupsList) {
            if (!Strings.isNullOrEmpty(group.getLdapGroupName())) {
                existingGroupsByLdapNameMap.put(group.getLdapGroupName(), group);
            }
        }
        Set<Group> ldapGroupsToDelete = Sets.newHashSet();
        if (LdapProperties.isSynchronizationDeleteExecutors()) {
            Set<Executor> ldapExecutors = executorDAO.getGroupChildren(importGroup);
            for (Executor executor : ldapExecutors) {
                if (executor instanceof Group) {
                    ldapGroupsToDelete.add((Group) executor);
                    log.info("Deleting " + executor);
                }
            }
        }
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        Map<String, SearchResult> groupResultsByDistinguishedName = Maps.newHashMap();
        for (String ou : LdapProperties.getSynchronizationOrganizationUnits()) {
            NamingEnumeration<SearchResult> list = dirContext.search(ou, OBJECT_CLASS_GROUP_FILTER, controls);
            while (list.hasMore()) {
                SearchResult searchResult = list.next();
                if (searchResult.getAttributes().get(ATTR_GROUP_MEMBER) == null) {
                    continue;
                }
                groupResultsByDistinguishedName.put(searchResult.getNameInNamespace(), searchResult);
            }
        }
        for (SearchResult searchResult : groupResultsByDistinguishedName.values()) {
            String name = getStringAttribute(searchResult, ATTR_ACCOUNT_NAME);
            String description = getStringAttribute(searchResult, LdapProperties.getSynchronizationGroupDescriptionAttribute());
            Group group = existingGroupsByLdapNameMap.get(name);
            if (group == null) {
                if (!LdapProperties.isSynchronizationCreateExecutors()) {
                    continue;
                }
                group = new Group(name, description);
                group.setLdapGroupName(name);
                log.info("Creating " + group);
                executorDAO.create(group);
                executorDAO.addExecutorsToGroup(Lists.newArrayList(group), importGroup);
                permissionDAO.setPermissions(importGroup, Lists.newArrayList(Permission.READ), group);
            } else {
                ldapGroupsToDelete.remove(group);
                if (LdapProperties.isSynchronizationUpdateExecutors()) {
                    List<String> updatedAttributes = Lists.newArrayList();
                    if (!Strings.isNullOrEmpty(description) && !Objects.equal(description, group.getDescription())) {
                        group.setDescription(description);
                        updatedAttributes.add("description");
                        executorDAO.update(group);
                    }
                    if (executorDAO.removeExecutorFromGroup(group, wasteGroup)) {
                        updatedAttributes.add("waste group removal");
                    }
                    if (executorDAO.addExecutorToGroup(group, importGroup)) {
                        updatedAttributes.add("import group addition");
                    }
                    if (!updatedAttributes.isEmpty()) {
                        log.info("Updating " + group);
                    }
                }
            }

            Set<Actor> actorsToDelete = Sets.newHashSet(executorDAO.getGroupActors(group));
            Set<Actor> actorsToAdd = Sets.newHashSet();
            Set<Actor> groupTargetActors = Sets.newHashSet();
            fillTargetActorsRecursively(dirContext, groupTargetActors, searchResult, groupResultsByDistinguishedName, actorsByDistinguishedName);
            for (Actor targetActor : groupTargetActors) {
                if (!actorsToDelete.remove(targetActor)) {
                    actorsToAdd.add(targetActor);
                }
            }
            if (actorsToAdd.size() > 0) {
                log.info("Adding to " + group + ": " + actorsToAdd);
                executorDAO.addExecutorsToGroup(actorsToAdd, group);
            }
            if (actorsToDelete.size() > 0) {
                executorDAO.removeExecutorsFromGroup(Lists.newArrayList(actorsToDelete), group);
            }
        }
        if (LdapProperties.isSynchronizationDeleteExecutors() && ldapGroupsToDelete.size() > 0) {
            executorDAO.removeExecutorsFromGroup(ldapGroupsToDelete, importGroup);
            executorDAO.addExecutorsToGroup(ldapGroupsToDelete, wasteGroup);
        }
    }

    private Pattern getPatternForMissedPeople() {
        if (patternForMissedPeople == null) {
            String providerUrl = LdapProperties.getAllProperties().get(Context.PROVIDER_URL);
            String dc = providerUrl.substring(providerUrl.lastIndexOf("/") + 1);
            patternForMissedPeople = Pattern.compile("," + dc, Pattern.CASE_INSENSITIVE);
        }
        return patternForMissedPeople;
    }

    private DirContext getContext() throws NamingException {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.putAll(LdapProperties.getAllProperties());
        return new InitialDirContext(env);
    }

    private Group loadGroup(Group group) {
        if (executorDAO.isExecutorExist(group.getName())) {
            group = executorDAO.getGroup(group.getName());
        } else {
            group = executorDAO.create(group);
            permissionDAO.setPermissions(group, Lists.newArrayList(Permission.READ, SystemPermission.LOGIN_TO_SYSTEM), ASystem.INSTANCE);
        }
        return group;
    }

    private String getStringAttribute(SearchResult searchResult, String name) throws NamingException {
        if (Utils.isNullOrEmpty(name)) {
            return null;
        }
        Attribute attribute = searchResult.getAttributes().get(name);
        if (attribute != null) {
            return attribute.get().toString();
        }
        return null;
    }

    private void fillTargetActorsRecursively(DirContext dirContext, Set<Actor> recursiveActors, SearchResult searchResult,
            Map<String, SearchResult> groupResultsByDistinguishedName, Map<String, Actor> actorsByDistinguishedName) throws NamingException {
        NamingEnumeration<String> namingEnum = (NamingEnumeration<String>) searchResult.getAttributes().get(ATTR_GROUP_MEMBER).getAll();
        while (namingEnum.hasMore()) {
            String executorDistinguishedName = namingEnum.next();
            SearchResult groupSearchResult = groupResultsByDistinguishedName.get(executorDistinguishedName);
            if (groupSearchResult != null) {
                fillTargetActorsRecursively(dirContext, recursiveActors, groupSearchResult, groupResultsByDistinguishedName,
                        actorsByDistinguishedName);
            } else {
                Actor actor = actorsByDistinguishedName.get(executorDistinguishedName);
                if (actor != null) {
                    recursiveActors.add(actor);
                } else {
                    Matcher m = getPatternForMissedPeople().matcher(executorDistinguishedName);
                    String executorPath = m.replaceAll("");
                    Attribute samAttribute = dirContext.getAttributes(executorPath).get(ATTR_ACCOUNT_NAME);
                    if (samAttribute != null) {
                        String executorName = samAttribute.get().toString();
                        log.debug("Executor name " + executorDistinguishedName + " fetched by invocation: " + executorName);
                        try {
                            Executor executor = executorDAO.getExecutor(executorName);
                            if (executor instanceof Actor) {
                                recursiveActors.add((Actor) executor);
                            }
                        } catch (ExecutorDoesNotExistException e) {
                            log.warn(e.getMessage() + " for '" + executorDistinguishedName + "'");
                        }
                    } else {
                        log.warn("Not found '" + executorDistinguishedName + "' neither in group or actor maps or by invocation");
                    }
                }
            }
        }
    }

}