/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014 The Billing Project, LLC
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.analytics.dao;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.killbill.billing.plugin.analytics.AnalyticsRefreshException;
import org.killbill.billing.plugin.analytics.dao.factory.BusinessAccountFactory;
import org.killbill.billing.plugin.analytics.dao.factory.BusinessBundleFactory;
import org.killbill.billing.plugin.analytics.dao.factory.BusinessSubscriptionTransitionFactory;
import org.killbill.billing.plugin.analytics.dao.model.BusinessAccountModelDao;
import org.killbill.billing.plugin.analytics.dao.model.BusinessBundleModelDao;
import org.killbill.billing.plugin.analytics.dao.model.BusinessSubscriptionTransitionModelDao;
import org.killbill.billing.util.audit.AccountAuditLogs;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.clock.Clock;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillLogService;
import org.osgi.service.log.LogService;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;

public class BusinessSubscriptionTransitionDao extends BusinessAnalyticsDaoBase {

    private final BusinessAccountDao businessAccountDao;
    private final BusinessBundleDao businessBundleDao;
    private final BusinessAccountFactory bacFactory;
    private final BusinessBundleFactory bbsFactory;
    private final BusinessSubscriptionTransitionFactory bstFactory;

    public BusinessSubscriptionTransitionDao(final OSGIKillbillLogService logService,
                                             final OSGIKillbillAPI osgiKillbillAPI,
                                             final OSGIKillbillDataSource osgiKillbillDataSource,
                                             final BusinessAccountDao businessAccountDao,
                                             final Executor executor,
                                             final Clock clock) {
        super(logService, osgiKillbillDataSource);
        this.businessAccountDao = businessAccountDao;
        this.businessBundleDao = new BusinessBundleDao(logService, osgiKillbillDataSource);
        bacFactory = new BusinessAccountFactory(logService, osgiKillbillAPI, osgiKillbillDataSource, clock);
        bbsFactory = new BusinessBundleFactory(logService, osgiKillbillAPI, osgiKillbillDataSource, executor, clock);
        bstFactory = new BusinessSubscriptionTransitionFactory(logService, osgiKillbillAPI, osgiKillbillDataSource, clock);
    }

    public void update(final UUID accountId, final AccountAuditLogs accountAuditLogs, final CallContext context) throws AnalyticsRefreshException {
        logService.log(LogService.LOG_INFO, "Starting rebuild of Analytics subscriptions for account " + accountId);

        // Recompute the account record
        final BusinessAccountModelDao bac = bacFactory.createBusinessAccount(accountId, accountAuditLogs, context);

        // Recompute all invoices and invoice items
        final Collection<BusinessSubscriptionTransitionModelDao> bsts = bstFactory.createBusinessSubscriptionTransitions(accountId,
                                                                                                                         accountAuditLogs,
                                                                                                                         bac.getAccountRecordId(),
                                                                                                                         bac.getTenantRecordId(),
                                                                                                                         context);

        // Recompute the bundle summary records
        final Collection<BusinessBundleModelDao> bbss = bbsFactory.createBusinessBundles(accountId,
                                                                                         accountAuditLogs,
                                                                                         bac.getAccountRecordId(),
                                                                                         bsts,
                                                                                         bac.getTenantRecordId(),
                                                                                         context);
        sqlDao.inTransaction(new Transaction<Void, BusinessAnalyticsSqlDao>() {
            @Override
            public Void inTransaction(final BusinessAnalyticsSqlDao transactional, final TransactionStatus status) throws Exception {
                updateInTransaction(bac, bbss, bsts, transactional, context);
                return null;
            }
        });

        logService.log(LogService.LOG_INFO, "Finished rebuild of Analytics subscriptions for account " + accountId);
    }

    private void updateInTransaction(final BusinessAccountModelDao bac,
                                     final Collection<BusinessBundleModelDao> bbss,
                                     final Collection<BusinessSubscriptionTransitionModelDao> bsts,
                                     final BusinessAnalyticsSqlDao transactional,
                                     final CallContext context) {
        // Update the subscription transitions
        transactional.deleteByAccountRecordId(BusinessSubscriptionTransitionModelDao.SUBSCRIPTION_TABLE_NAME,
                                              bac.getAccountRecordId(),
                                              bac.getTenantRecordId(),
                                              context);

        for (final BusinessSubscriptionTransitionModelDao bst : bsts) {
            transactional.create(bst.getTableName(), bst, context);
        }

        // Update the summary table per bundle
        businessBundleDao.updateInTransaction(bbss,
                                              bac.getAccountRecordId(),
                                              bac.getTenantRecordId(),
                                              transactional,
                                              context);

        // Update BAC
        businessAccountDao.updateInTransaction(bac, transactional, context);
    }
}