import {
  editDashboard,
  getDashboardCard,
  getNotebookStep,
  modal,
  openNotebook,
  restore,
  saveDashboard,
  selectDashboardFilter,
  visitDashboard,
  visitQuestion,
} from "__support__/e2e/cypress";
import { SAMPLE_DATABASE } from "__support__/e2e/cypress_sample_database";

const { PRODUCTS, PRODUCTS_ID } = SAMPLE_DATABASE;

const questionDetails = {
  display: "table",
  query: {
    "source-query": {
      "source-table": PRODUCTS_ID,
      aggregation: [
        ["count"],
        ["sum", ["field", PRODUCTS.PRICE, null]],
        ["sum", ["field", PRODUCTS.RATING, null]],
      ],
      breakout: [["field", PRODUCTS.CATEGORY, null]],
    },
    fields: [
      ["field", PRODUCTS.CATEGORY, null],
      ["field", "sum", { "base-type": "type/Float" }],
      ["field", "sum_2", { "base-type": "type/Float" }],
      ["expression", "Custom Column"],
    ],
    expressions: {
      "Custom Column": ["+", 1, 1],
    },
  },
};

const filterDetails = {
  id: "b6f1865b",
  name: "Date filter",
  slug: "date",
  type: "date/month-year",
  sectionId: "date",
};

const dashboardDetails = {
  name: "Filters",
  parameters: [filterDetails],
};

describe("issue 19745", () => {
  beforeEach(() => {
    restore();
    cy.signInAsAdmin();
  });

  it("should unwrap the inner query when removing all non-field clauses (metabase#19745)", () => {
    cy.createQuestionAndDashboard({ questionDetails, dashboardDetails }).then(
      ({ body: { card_id, dashboard_id } }) => {
        visitQuestion(card_id);

        // this should modify the query and remove the second stage
        openNotebook();
        removeExpression("Custom Column");
        saveQuestion();

        // as we select all columns in the first stage of the query,
        // it should be possible to map a filter to a selected column
        visitDashboard(dashboard_id);
        editDashboard();
        cy.findByText("Date filter").click();
        selectDashboardFilter(getDashboardCard(), "Created At");
        saveDashboard();
      },
    );
  });
});

function removeExpression(name) {
  getNotebookStep("expression", { stage: 1 }).within(() => {
    cy.findByText(name).within(() => {
      cy.icon("close").click();
    });
  });
}

function saveQuestion() {
  cy.intercept("PUT", "/api/card/*").as("saveQuestion");
  cy.findByText("Save").click();
  modal().within(() => cy.button("Save").click());
  cy.wait("@saveQuestion");
}
