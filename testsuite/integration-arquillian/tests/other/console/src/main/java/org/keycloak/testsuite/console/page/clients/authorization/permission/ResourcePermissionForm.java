/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.console.page.clients.authorization.permission;

import static org.keycloak.testsuite.util.UIUtils.performOperationWithPageReload;

import org.jboss.arquillian.graphene.page.Page;
import org.keycloak.representations.idm.authorization.AbstractPolicyRepresentation;
import org.keycloak.representations.idm.authorization.ClientPolicyRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.GroupPolicyRepresentation;
import org.keycloak.representations.idm.authorization.JSPolicyRepresentation;
import org.keycloak.representations.idm.authorization.ResourcePermissionRepresentation;
import org.keycloak.representations.idm.authorization.RolePolicyRepresentation;
import org.keycloak.representations.idm.authorization.RulePolicyRepresentation;
import org.keycloak.representations.idm.authorization.TimePolicyRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;
import org.keycloak.testsuite.console.page.clients.authorization.policy.ClientPolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.GroupPolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.JSPolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.PolicySelect;
import org.keycloak.testsuite.console.page.clients.authorization.policy.RolePolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.RulePolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.TimePolicy;
import org.keycloak.testsuite.console.page.clients.authorization.policy.UserPolicy;
import org.keycloak.testsuite.console.page.fragment.ModalDialog;
import org.keycloak.testsuite.console.page.fragment.MultipleStringSelect2;
import org.keycloak.testsuite.console.page.fragment.OnOffSwitch;
import org.keycloak.testsuite.page.Form;
import org.keycloak.testsuite.util.UIUtils;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.ui.Select;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class ResourcePermissionForm extends Form {

    @FindBy(id = "name")
    private WebElement name;

    @FindBy(id = "description")
    private WebElement description;

    @FindBy(id = "decisionStrategy")
    private Select decisionStrategy;

    @FindBy(xpath = ".//div[@class='onoffswitch' and ./input[@id='applyToResourceTypeFlag']]")
    private OnOffSwitch resourceTypeSwitch;

    @FindBy(id = "resourceType")
    private WebElement resourceType;

    @FindBy(xpath = "//i[contains(@class,'pficon-delete')]")
    private WebElement deleteButton;

    @FindBy(xpath = "//div[@class='modal-dialog']")
    protected ModalDialog modalDialog;

    @FindBy(id = "s2id_policies")
    private PolicySelect policySelect;

    @FindBy(id = "s2id_resources")
    private MultipleStringSelect2 resourceSelect;

    @FindBy(id = "create-policy")
    private Select createPolicySelect;

    @Page
    private RolePolicy rolePolicy;

    @Page
    private UserPolicy userPolicy;

    @Page
    private ClientPolicy clientPolicy;

    @Page
    private JSPolicy jsPolicy;

    @Page
    private TimePolicy timePolicy;

    @Page
    private RulePolicy rulePolicy;

    @Page
    private GroupPolicy groupPolicy;

    public void populate(ResourcePermissionRepresentation expected, boolean save) {
        UIUtils.setTextInputValue(name, expected.getName());
        UIUtils.setTextInputValue(description, expected.getDescription());
        decisionStrategy.selectByValue(expected.getDecisionStrategy().name());

        resourceTypeSwitch.setOn(expected.getResourceType() != null);

        if (expected.getResourceType() != null) {
            UIUtils.setTextInputValue(resourceType, expected.getResourceType());
        } else {
            resourceTypeSwitch.setOn(false);
            resourceSelect.update(expected.getResources());
        }

        if (expected.getPolicies() != null) {
            policySelect.update(expected.getPolicies());
        }

        if (save) {
            save();
        }
    }

    public void delete() {
        deleteButton.click();
        modalDialog.confirmDeletion();
    }

    public ResourcePermissionRepresentation toRepresentation() {
        ResourcePermissionRepresentation representation = new ResourcePermissionRepresentation();

        representation.setName(UIUtils.getTextInputValue(name));
        representation.setDescription(UIUtils.getTextInputValue(description));
        representation.setDecisionStrategy(DecisionStrategy.valueOf(UIUtils.getTextFromElement(decisionStrategy.getFirstSelectedOption()).toUpperCase()));
        representation.setPolicies(policySelect.getSelected());
        String inputValue = UIUtils.getTextInputValue(resourceType);

        if (!"".equals(inputValue)) {
            representation.setResourceType(inputValue);
        }

        representation.setResources(resourceSelect.getSelected());

        return representation;
    }

    public void createPolicy(AbstractPolicyRepresentation expected) {
        performOperationWithPageReload(() -> createPolicySelect.selectByValue(expected.getType()));

        if ("role".equals(expected.getType())) {
            rolePolicy.form().populate((RolePolicyRepresentation) expected, true);
        } else if ("user".equalsIgnoreCase(expected.getType())) {
            userPolicy.form().populate((UserPolicyRepresentation) expected, true);
        } else if ("client".equalsIgnoreCase(expected.getType())) {
            clientPolicy.form().populate((ClientPolicyRepresentation) expected, true);
        } else if ("js".equalsIgnoreCase(expected.getType())) {
            jsPolicy.form().populate((JSPolicyRepresentation) expected, true);
        } else if ("time".equalsIgnoreCase(expected.getType())) {
            timePolicy.form().populate((TimePolicyRepresentation) expected, true);
        } else if ("rules".equalsIgnoreCase(expected.getType())) {
            rulePolicy.form().populate((RulePolicyRepresentation) expected, true);
        } else if ("group".equalsIgnoreCase(expected.getType())) {
            groupPolicy.form().populate((GroupPolicyRepresentation) expected, true);
        }
    }
}